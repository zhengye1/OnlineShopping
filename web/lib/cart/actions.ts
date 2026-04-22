"use server" // ⚠️ 整個 file 頂：所有 export 都係 server action
import {cookies} from "next/headers";
import {revalidatePath} from "next/cache";
import type {Cart} from "@/lib/cart/types";

const CART_COOKIE="cart"
const COOKIE_MAX_AGE = 60 * 60 * 24 * 30;  // 30 days in seconds

export async function addToCart(productId: number, quantity: number){
    const store = await cookies();
    const raw = store.get(CART_COOKIE)?.value;

    // Parse existing cart (Same Pattern as getCart)
    let cart:Cart = { items:[] };
    if (raw){
        try{
            cart = JSON.parse(raw);
        }catch{
            /* corrupt cookies, start fresh */
        }
    }

    // Merge: if product already in cart, accumulate quantity
    const existing  = cart.items.find(item => item.productId === productId);
    if (existing){
        existing.quantity += quantity;
    }else{
        cart.items.push({productId, quantity});
    }

    // Write cookie back
    store.set(CART_COOKIE, JSON.stringify(cart), {
        path:"/",
        maxAge: COOKIE_MAX_AGE,
    });

    // Tell Next: /cart page's cache HTML is stale, re-render next request
    revalidatePath("/cart");
}
export async function removeFromCart(productId: number) {
    const store = await cookies();
    const raw = store.get(CART_COOKIE)?.value;
    if (!raw) return; // no cookies then nothing to do
    let cart: Cart;
    try {
        cart = JSON.parse(raw);
    } catch {
        return; // corrupt cookies
    }
    cart.items = cart.items.filter(item => item.productId !== productId);
    store.set(CART_COOKIE, JSON.stringify(cart), {
        path: "/",
        maxAge: COOKIE_MAX_AGE
    });
    revalidatePath("/cart");
}
export async function updateQuantity(productId: number, formData: FormData){
    const quantity = Number(formData.get("quantity"));
    // Edge case: 0 or negative -> treat as remove
    if (!Number.isFinite(quantity) || quantity < 1){
        return removeFromCart(productId);
    }

    const store = await cookies();
    const raw = store.get(CART_COOKIE)?.value;
    if (!raw) return ;
    let cart:Cart;
    try {
        cart = JSON.parse(raw);
    }catch{
        return ;
    }

    const item = cart.items.find(i => i.productId === productId);
    if (!item) return ;
    item.quantity = quantity;

    store.set(CART_COOKIE, JSON.stringify(cart), {
        path:"/",
        maxAge: COOKIE_MAX_AGE,
    });

    revalidatePath("/cart");
}