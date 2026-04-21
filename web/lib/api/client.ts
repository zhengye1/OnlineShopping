import type { components } from "./types";

type FeedResponse = components["schemas"]["FeedResponse"];

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!res.ok) {
    throw new Error(`API ${path} failed: ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<T>;
}

export function getFeed(): Promise<FeedResponse> {
  return apiGet<FeedResponse>("/api/feed");
}

type ProductResponse = components["schemas"]["ProductResponse"];
export function getProduct(id: number): Promise<ProductResponse>{
  return apiGet<ProductResponse>(`/api/products/${id}`);
}