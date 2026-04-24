"use server"
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type {components} from "@/lib/api/types";

type AuthResponse = components["schemas"]["AuthResponse"];
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const AUTH_COOKIE = "auth_token";
const COOKIE_MAX_AGE = 60 * 60 * 24 * 7; // 7 days

export async function login(formData: FormData){
    const username = String(formData.get("username") ?? "").trim();
    const password = String(formData.get("password") ?? "");

    if (!username || !password) {
        return { error: "Username and password required"};
    }

    const res = await fetch(`${API_BASE_URL}/api/auth/login`,{
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({username, password}),
        cache: "no-store"
    });

    if (!res.ok){
        return {error: "Invalid username or password"};
    }

    const data = (await res.json()) as AuthResponse;
    const store = await cookies();
    store.set(AUTH_COOKIE, data.token!, {
        path: "/",
        maxAge: COOKIE_MAX_AGE,
        httpOnly: true,
        sameSite: "lax", //csrf
        secure: process.env.NODE_ENV === "production",
    });
     redirect("/");
}

export async function logout(){
    const store = await cookies();
    store.delete("auth_token");
    redirect("/");
}