"use server" // ⚠️ 整個 file 頂：所有 export 都係 server action
import {cookies} from "next/headers";
import {revalidatePath} from "next/cache";
import type {Cart} from "@/lib/cart/types";
import {authFetch, TokenExpiredError} from "@/lib/api/authFetch";
import {redirect} from "next/navigation";
import {BACKEND_URL} from "@/lib/config";

const CART_COOKIE="cart"
const AUTH_COOKIE = "auth_token"
const COOKIE_MAX_AGE = 60 * 60 * 24 * 30;  // 30 days in seconds


export async function addToCart(productId: number, quantity: number){
    const token = await getAuthToken();
    try {
        if (token){
            await addToBackendCart(token, productId, quantity);
        }else{
            await addToGuestCart(productId, quantity);
        }
    }catch(err){
        if (err instanceof TokenExpiredError){
            const store = await cookies();
            store.delete(AUTH_COOKIE);
            redirect(`/login?next=/products`);
        }
        throw err;
    }

    // Tell Next: /cart page's cache HTML is stale, re-render next request
    revalidatePath("/cart");
    revalidatePath("/");
}
export async function removeFromCart(productId: number) {
    const token = await getAuthToken();
    try {
        if (token) {
            await removeFromBackendCart(token, productId);
        } else {
            await removeFromGuestCart(productId);
        }
    }catch(err){
        if (err instanceof TokenExpiredError){
            const store = await cookies();
            store.delete(AUTH_COOKIE);
            redirect(`/login?next=products`)
        }
        throw err;
    }
    revalidatePath("/cart");
    revalidatePath("/");
}
export async function updateQuantity(productId: number, formData: FormData){
    const quantity = Number(formData.get("quantity"));
    // Edge case: 0 or negative -> treat as remove
    if (!Number.isFinite(quantity) || quantity < 1){
        return removeFromCart(productId);
    }
    const token = await getAuthToken();
    try {
        if (token) {
            await updateBackendCartQuantity(token, productId, quantity);
        } else {
            await updateGuestCartQuantity(productId, quantity);
        }
    }catch(err){
        if (err instanceof TokenExpiredError){
            const store = await cookies();
            store.delete(AUTH_COOKIE);
            redirect(`/login?next=products`)
        }
        throw err;
    }
    revalidatePath("/cart");
    revalidatePath("/");
}

export async function clearCart(){
    const token = await getAuthToken();
    try {
        if (token) {
            await clearBackendCart(token);
        } else {
            await clearGuestCart();
        }
    }catch(err){
        if (err instanceof TokenExpiredError){
            const store = await cookies();
            store.delete(AUTH_COOKIE);
            redirect(`/login?next=products`);
        }
    }
    revalidatePath("/cart");
    revalidatePath("/");
}

async function getAuthToken(): Promise<string | null> {
    const store = await cookies();
    return store.get(AUTH_COOKIE)?.value ?? null;
}

async function addToBackendCart(token:string, productId:number, quantity:number){
    const res = await authFetch(token, `${BACKEND_URL}/api/cart`,{
        headers:{"Content-Type": "application/json"},
        method: "POST",
        body: JSON.stringify({productId, quantity}),
    });
    if (!res.ok) {
        throw new Error("add to cart failed");
    }
}

async function addToGuestCart(productId:number, quantity:number){
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
    if (existing) {
        existing.quantity += quantity;
    }else{
        cart.items.push({productId, quantity});
    }

    store.set(CART_COOKIE, JSON.stringify(cart),{
        path:"/",
        maxAge: COOKIE_MAX_AGE,
    });
}
async function removeFromBackendCart(token:string,productId:number){
    const res = await authFetch(token, `${BACKEND_URL}/api/cart/${productId}`,{
        method: "DELETE",
    });
    if (!res.ok) {
        throw new Error("Delete have issue");
    }
}

async function removeFromGuestCart(productId:number){
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
}
async function updateBackendCartQuantity(token: string, productId: number, quantity: number) {
    const res = await authFetch(token, `${BACKEND_URL}/api/cart/${productId}?quantity=${quantity}`,{
        method: "PUT",
    });
    if (!res.ok) {
        throw new Error("Update cart failed");
    }
}

async function updateGuestCartQuantity(productId:number, quantity:number){
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
}

async function clearBackendCart(token: string){
    const res = await authFetch(token, `${BACKEND_URL}/api/cart`,{
        headers:{Authorization: `Bearer ${token}`},
        method: "DELETE",
    });
    if (!res.ok) {
        throw new Error("Clean backend cart failed");
    }
}
async function clearGuestCart(){
    const store = await cookies();
    store.delete(CART_COOKIE);
}