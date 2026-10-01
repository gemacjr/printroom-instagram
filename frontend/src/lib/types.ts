export type MediaType = "IMAGE" | "VIDEO";
export type PostStatus = "PROCESSING" | "PUBLISHED" | "FAILED";
export type MediaStatus = "PENDING_UPLOAD" | "READY" | "FAILED";

export interface UserSummary {
  userId: string;
  username: string;
  displayName: string;
}

export interface UserProfile extends UserSummary {
  bio?: string;
  followerCount: number;
  followingCount: number;
  postCount: number;
  createdAt: number;
  isFollowing?: boolean;
}

export interface AuthResponse {
  token: string;
  expiresIn: number;
  user: UserProfile;
}

export interface MediaItem {
  mediaId: string;
  mediaType: MediaType;
  url: string;
  width?: number;
  height?: number;
}

export interface Post {
  postId: string;
  author: UserSummary;
  caption: string;
  status: PostStatus;
  media: MediaItem[];
  createdAt: number;
  publishedAt?: number;
}

export interface Page<T> {
  items: T[];
  nextCursor?: string | null;
}

export interface PartUrl {
  partNumber: number;
  url: string;
}

export interface InitiateUploadResponse {
  mediaId: string;
  mediaType: MediaType;
  uploadType: "SINGLE" | "MULTIPART";
  uploadUrl?: string;
  partSize?: number;
  parts?: PartUrl[];
  expiresAt: string;
}

export interface MediaResponse {
  mediaId: string;
  mediaType: MediaType;
  status: MediaStatus;
  url?: string;
  failureReason?: string;
}

export interface FollowResult {
  followeeId: string;
  following: boolean;
  changed: boolean;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
}
