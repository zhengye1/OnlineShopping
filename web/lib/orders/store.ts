import type {Order} from "./types";
import {cookies} from "next/headers";
import {authFetch} from "@/lib/api/authFetch";
import {BACKEND_URL} from "@/lib/config";

const AUTH_COOKIE = "auth_token";
export async function getMyOrders():Promise<Order[]>{
    const store = await cookies();
    const token = store.get(AUTH_COOKIE)?.value;
    if (!token) throw new Error("Not authenticated");

    const res = await authFetch(token, `${BACKEND_URL}/api/orders`);
    if (!res.ok) {
        throw new Error("Get orders failed");
    }
    return (await res.json()) as Order[];
}

export async function getOrderDetail(id: string):Promise<Order>{
    const store = await cookies();
    const token = store.get(AUTH_COOKIE)?.value;
    if (!token) throw new Error("Not authenticated");

    const res = await authFetch(token, `${BACKEND_URL}/api/orders/${id}`);
    if (!res.ok) {
        if (res.status === 404) throw new Error("Order not found");
        throw new Error(`Failed to fetch order: ${res.status}`);
    }
    return (await res.json()) as Order;
}