import {cookies} from "next/headers";
import type {Cart} from "./types";

const CART_COOKIE = "cart";
const EMPTY_CART: Cart = {items: []};

export async function getCart(): Promise<Cart> {
    const store = await cookies();
    const raw = store.get(CART_COOKIE)?.value;
    if (!raw) return EMPTY_CART;
    try {
        return JSON.parse(raw) as Cart;
    } catch {
        return EMPTY_CART;
    }
}