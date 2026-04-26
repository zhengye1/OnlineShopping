import {cookies} from "next/headers";
import type {Cart} from "./types";
import {authFetch, TokenExpiredError} from "@/lib/api/authFetch";

const CART_COOKIE = "cart";
const AUTH_COOKIE = "auth_token";
const API_BASE_URL =
    process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const EMPTY_CART: Cart = {items: []};

async function getBackendCart(token:string):Promise<Cart>{
    const res = await authFetch(token, `${API_BASE_URL}/api/cart`,{
    });
    if (!res.ok) {
        return EMPTY_CART;
    }
    const data = (await res.json()) as {
        items?: { productId: number, quantity: number}[];
    };

    return{
        items: (data.items ?? []).map(
            i =>({
                productId: i.productId,
                quantity: i.quantity,}
        )),
    };
}

function getGuestCart(raw: string | undefined): Cart{
    if (!raw) return EMPTY_CART;
    try {
        return JSON.parse(raw) as Cart;
    }catch{
        return EMPTY_CART;
    }
}
export async function getCart(): Promise<Cart> {

    const store = await cookies();
    const token = store.get(AUTH_COOKIE)?.value;

    // logged in
    try {
        if (token) {
            return await getBackendCart(token);
        }
    }catch(err){
        if (err instanceof TokenExpiredError){
            // Server Component 唔可以 delete cookie
            // 留低個 bad cookie，下次 mutation 會清
            return getGuestCart(store.get(CART_COOKIE)?.value);
        }
        throw err;
    }
    return getGuestCart(store.get(CART_COOKIE)?.value);
}