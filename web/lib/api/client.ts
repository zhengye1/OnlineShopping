import type { components } from "./types";

type FeedResponse = components["schemas"]["FeedResponse"];
type ProductResponse = components["schemas"]["ProductResponse"];
type PageResponse = components["schemas"]["PageResponseProductResponse"];

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
  return (await res.json()) as T;
}

export function getFeed(): Promise<FeedResponse> {
  return apiGet<FeedResponse>("/api/feed");
}

export function getProduct(id: number): Promise<ProductResponse>{
  return apiGet<ProductResponse>(`/api/products/${id}`);
}

export function getProducts(page=0, size=20): Promise<PageResponse>{
  return apiGet<PageResponse>(
      `/api/products?page=${page}&size=${size}&sortBy=createdAt&direction=desc`
  );
}