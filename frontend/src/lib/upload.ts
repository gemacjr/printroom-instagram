import { api } from "./api";
import type { InitiateUploadResponse } from "./types";

export const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
export const MAX_VIDEO_BYTES = 4 * 1024 * 1024 * 1024;
export const ACCEPTED_TYPES = [
  "image/jpeg", "image/png", "image/webp", "image/gif",
  "video/mp4", "video/quicktime", "video/webm",
];
const PART_CONCURRENCY = 4;

export type Progress = (fraction: number) => void;

/** Returns a user-facing problem with the file, or null if it can be uploaded. */
export function validateFile(file: Pick<File, "type" | "size">): string | null {
  if (!ACCEPTED_TYPES.includes(file.type)) return "Choose a JPEG, PNG, WebP, GIF, MP4, MOV or WebM file.";
  const isVideo = file.type.startsWith("video/");
  const max = isVideo ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;
  if (file.size > max) return isVideo ? "Videos can be up to 4 GB." : "Photos can be up to 8 MB.";
  if (file.size === 0) return "This file is empty.";
  return null;
}

/** Byte ranges [start, end) for each multipart part; the last part takes the remainder. */
export function planParts(totalBytes: number, partSize: number, partCount: number) {
  return Array.from({ length: partCount }, (_, i) => {
    const start = i * partSize;
    return { partNumber: i + 1, start, end: Math.min(start + partSize, totalBytes) };
  });
}

/** PUT with upload progress (fetch has no upload progress events). Resolves with the ETag header. */
export function putWithProgress(
  url: string,
  body: Blob,
  contentType: string | null,
  onProgress: (loaded: number) => void,
): Promise<string> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", url);
    if (contentType) xhr.setRequestHeader("Content-Type", contentType);
    xhr.upload.onprogress = (e) => onProgress(e.loaded);
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve(xhr.getResponseHeader("ETag") ?? "");
      else reject(new Error(`Upload failed with status ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error("Network error while uploading"));
    xhr.send(body);
  });
}

/**
 * Direct-to-S3 upload. Small files: one presigned PUT. Large files: presigned multipart parts uploaded
 * in parallel, then the API stitches them together. The API server never touches the bytes.
 */
export async function uploadFile(file: File, onProgress: Progress = () => {}): Promise<string> {
  const init: InitiateUploadResponse = await api.initiateUpload({ contentType: file.type, sizeBytes: file.size });

  if (init.uploadType === "SINGLE") {
    await putWithProgress(init.uploadUrl!, file, file.type, (loaded) => onProgress(loaded / file.size));
    onProgress(1);
    return init.mediaId;
  }

  const parts = planParts(file.size, init.partSize!, init.parts!.length);
  const loadedByPart = new Map<number, number>();
  const report = () => {
    let loaded = 0;
    loadedByPart.forEach((v) => (loaded += v));
    onProgress(Math.min(1, loaded / file.size));
  };
  const etags: { partNumber: number; eTag: string }[] = [];
  const queue = [...parts];

  async function worker() {
    for (let part = queue.shift(); part; part = queue.shift()) {
      const url = init.parts![part.partNumber - 1].url;
      const n = part.partNumber;
      // Part URLs are not signed with Content-Type, so don't send one.
      const eTag = await putWithProgress(url, file.slice(part.start, part.end), null, (loaded) => {
        loadedByPart.set(n, loaded);
        report();
      });
      etags.push({ partNumber: n, eTag });
    }
  }

  await Promise.all(Array.from({ length: Math.min(PART_CONCURRENCY, parts.length) }, worker));
  await api.completeUpload(init.mediaId, etags.sort((a, b) => a.partNumber - b.partNumber));
  onProgress(1);
  return init.mediaId;
}
