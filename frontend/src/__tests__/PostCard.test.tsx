import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { PostCard } from "@/components/PostCard";
import type { Post } from "@/lib/types";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));

const base: Post = {
  postId: "p1",
  author: { userId: "u1", username: "ada", displayName: "Ada" },
  caption: "Morning light",
  status: "PUBLISHED",
  media: [
    { mediaId: "m1", mediaType: "IMAGE", url: "https://cdn/1.jpg", width: 1080, height: 720 },
    { mediaId: "m2", mediaType: "IMAGE", url: "https://cdn/2.jpg" },
  ],
  createdAt: Date.now(),
  publishedAt: Date.now(),
};

describe("PostCard", () => {
  it("renders author, caption and first image", () => {
    render(<PostCard post={base} />);
    expect(screen.getAllByText("ada")[0]).toHaveAttribute("href", "/u/ada");
    expect(screen.getByText("Morning light")).toBeInTheDocument();
    expect(screen.getByRole("img")).toHaveAttribute("src", "https://cdn/1.jpg");
  });

  it("pages through a carousel", () => {
    render(<PostCard post={base} />);
    expect(screen.getByText("1 of 2")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByRole("img")).toHaveAttribute("src", "https://cdn/2.jpg");
    expect(screen.getByText("2 of 2")).toBeInTheDocument();
  });

  it("shows processing state instead of media", () => {
    render(<PostCard post={{ ...base, status: "PROCESSING" }} />);
    expect(screen.getByText(/appears once processing finishes/)).toBeInTheDocument();
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("offers delete only on your own post", () => {
    const onDelete = vi.fn();
    const { rerender } = render(<PostCard post={base} onDelete={onDelete} />);
    expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
    rerender(<PostCard post={base} isOwn onDelete={onDelete} />);
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onDelete).toHaveBeenCalledWith("p1");
  });
});
