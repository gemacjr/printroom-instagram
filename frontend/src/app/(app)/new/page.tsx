"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { uploadFile, validateFile, ACCEPTED_TYPES } from "@/lib/upload";

const MAX_ITEMS = 10;

interface Pick {
  id: string;
  file: File;
  preview: string;
  progress: number;
  mediaId?: string;
  error?: string;
}

export default function NewPostPage() {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [picks, setPicks] = useState<Pick[]>([]);
  const [caption, setCaption] = useState("");
  const [stage, setStage] = useState<"idle" | "uploading" | "publishing">("idle");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => () => picks.forEach((p) => URL.revokeObjectURL(p.preview)), [picks]);

  function addFiles(list: FileList | null) {
    if (!list) return;
    setError(null);
    const next: Pick[] = [];
    for (const file of Array.from(list)) {
      const problem = validateFile(file);
      if (problem) {
        setError(`${file.name}: ${problem}`);
        continue;
      }
      next.push({ id: crypto.randomUUID(), file, preview: URL.createObjectURL(file), progress: 0 });
    }
    setPicks((prev) => [...prev, ...next].slice(0, MAX_ITEMS));
  }

  function update(id: string, patch: Partial<Pick>) {
    setPicks((prev) => prev.map((p) => (p.id === id ? { ...p, ...patch } : p)));
  }

  async function publish() {
    if (picks.length === 0) return;
    setError(null);
    setStage("uploading");
    try {
      // Uploads go straight to S3 in parallel; the API only sees metadata.
      const mediaIds = await Promise.all(
        picks.map(async (p) => {
          if (p.mediaId) return p.mediaId;
          try {
            const id = await uploadFile(p.file, (f) => update(p.id, { progress: f }));
            update(p.id, { mediaId: id, progress: 1 });
            return id;
          } catch (e) {
            update(p.id, { error: e instanceof Error ? e.message : "Upload failed" });
            throw e;
          }
        }),
      );
      setStage("publishing");
      const post = await api.createPost({ caption, mediaIds });
      router.push(post.status === "PUBLISHED" ? "/" : `/p/${post.postId}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Upload failed. Your files are still selected; try again.");
      setStage("idle");
    }
  }

  const busy = stage !== "idle";

  return (
    <div className="max-w-[560px]">
      <h1 className="mb-6 font-serif text-3xl">New post</h1>

      <div className="print">
        {picks.length === 0 ? (
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              addFiles(e.dataTransfer.files);
            }}
            className="grid aspect-[4/3] w-full place-items-center border border-dashed border-rule bg-paper text-center"
          >
            <span>
              <span className="block font-serif text-2xl">Choose photos or videos</span>
              <span className="mt-1 block text-sm text-silver">Or drop them here. Up to 10 items, photos up to 8 MB, videos up to 4 GB.</span>
            </span>
          </button>
        ) : (
          <ul className="grid grid-cols-3 gap-2">
            {picks.map((p) => (
              <li key={p.id} className="relative aspect-square overflow-hidden bg-paper">
                {p.file.type.startsWith("video/") ? (
                  <video src={p.preview} muted className="h-full w-full object-cover" />
                ) : (
                  // eslint-disable-next-line @next/next/no-img-element -- local object URL preview
                  <img src={p.preview} alt={p.file.name} className="h-full w-full object-cover" />
                )}
                {busy && (
                  <div className="absolute inset-x-0 bottom-0 h-1.5 bg-white/70">
                    <div className="h-full bg-cyan transition-[width]" style={{ width: `${Math.round(p.progress * 100)}%` }} />
                  </div>
                )}
                {p.error && <span className="absolute inset-0 grid place-items-center bg-white/85 p-2 text-center text-xs text-danger">{p.error}</span>}
                {!busy && (
                  <button
                    type="button"
                    aria-label={`Remove ${p.file.name}`}
                    onClick={() => setPicks((prev) => prev.filter((x) => x.id !== p.id))}
                    className="absolute right-1 top-1 grid h-6 w-6 place-items-center rounded-full bg-white/90 text-ink"
                  >
                    ×
                  </button>
                )}
              </li>
            ))}
            {picks.length < MAX_ITEMS && !busy && (
              <li>
                <button
                  type="button"
                  onClick={() => inputRef.current?.click()}
                  className="grid aspect-square w-full place-items-center border border-dashed border-rule text-silver"
                >
                  Add more
                </button>
              </li>
            )}
          </ul>
        )}

        <input
          ref={inputRef}
          type="file"
          multiple
          accept={ACCEPTED_TYPES.join(",")}
          className="sr-only"
          onChange={(e) => {
            addFiles(e.target.files);
            e.target.value = "";
          }}
        />

        <label className="mt-4 block">
          <span className="mb-1 block text-sm font-medium">Caption</span>
          <textarea
            value={caption}
            onChange={(e) => setCaption(e.target.value)}
            maxLength={2200}
            rows={3}
            disabled={busy}
            className="field caption resize-y"
            placeholder="Write something about this"
          />
          <span className="mt-1 block text-right text-xs text-silver tabular-nums">{caption.length} / 2200</span>
        </label>

        {error && <p role="alert" className="mt-2 text-sm text-danger">{error}</p>}

        <div className="mt-4 flex justify-end">
          <button type="button" onClick={publish} disabled={busy || picks.length === 0} className="btn-primary px-6 py-2.5">
            {stage === "uploading" ? "Uploading…" : stage === "publishing" ? "Publishing…" : "Publish"}
          </button>
        </div>
      </div>
    </div>
  );
}
