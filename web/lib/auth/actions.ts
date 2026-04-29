"use server"
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type {components} from "@/lib/api/types";
import type {Cart} from "@/lib/cart/types";
import {migrateCart} from "@/lib/cart/migrate";
import {revalidatePath} from "next/cache";
import {BACKEND_URL} from "@/lib/config";

type AuthResponse = components["schemas"]["AuthResponse"];
const AUTH_COOKIE = "auth_token";
const COOKIE_MAX_AGE = 60 * 60 * 24 * 7; // 7 days

export async function login(formData: FormData){
    const username = String(formData.get("username") ?? "").trim();
    const password = String(formData.get("password") ?? "");

    if (!username || !password) {
        return { error: "Username and password required"};
    }

    const next = String(formData.get("next") ?? "/");

    const res = await fetch(`${BACKEND_URL}/api/auth/login`,{
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({username, password}),
        cache: "no-store"
    });

    if (!res.ok){
        return {error: "Invalid username or password"};
    }

    const data = (await res.json()) as AuthResponse;
    await setAuthCookie(data.token!);
    // migrate guest cart
    await migrateGuestCartIfAny(data.token!);
    const safeNext = next.startsWith("/") &&
        !next.startsWith("//") ? next : "/"
    revalidatePath("/", "layout");
    redirect(safeNext);
}

export async function logout(){
    const store = await cookies();
    store.delete("auth_token");
    revalidatePath("/", "layout");
    redirect("/");
}

export async function register(formData: FormData){
    const username = String(formData.get("username") ?? "").trim();
    const email = String(formData.get("email") ?? "").trim();
    const password = String(formData.get("password") ?? "");

    if (!username || !email || !password){
        return {error: "All fields required"};
    }

    if (password.length < 8){
        return {error: "Password must be at least 8 characters"};
    }
    const next = String(formData.get("next") ?? "/");

    const res = await fetch(`${BACKEND_URL}/api/auth/register`, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({username, email, password}),
        cache: "no-store",
    });

    if (!res.ok){
        // Try to extract backend validation message
        let message = "Registration failed";
        try {
            const body = await res.json();
            if (typeof body?.message === "string") message = body.message;
        }catch {
            /* ignore parse error */
        }
        return {error: message};
    }

    const data = (await res.json()) as AuthResponse;
    await setAuthCookie(data.token!);
    await migrateGuestCartIfAny(data.token!);
    const safeNext = next.startsWith("/") &&
        !next.startsWith("//") ? next : "/"
    revalidatePath("/", "layout");
    redirect(safeNext);
}

async function setAuthCookie(token: string){
    const store = await cookies();
    store.set(AUTH_COOKIE, token, {
        path:"/",
        maxAge:COOKIE_MAX_AGE,
        httpOnly: true,
        sameSite: "lax",
        secure: process.env.NODE_ENV === "production",
    });
}

async function migrateGuestCartIfAny(token: string) {
    const store = await cookies();
    const rawCart = store.get("cart")?.value;
    if (!rawCart) return;
    try {
        const guestCart: Cart = JSON.parse(rawCart);
        await migrateCart(token, guestCart);
        store.delete("cart");
    } catch { /* skip corrupt */ }
}