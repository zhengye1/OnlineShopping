"use server";
import {cookies} from "next/headers";
import {redirect} from "next/navigation";
import {authFetch, TokenExpiredError} from "@/lib/api/authFetch";
import {BACKEND_URL} from "@/lib/config";
import {revalidatePath} from "next/cache";
import {CheckoutState} from "./types";

const AUTH_COOKIE = "auth_token";


export async function placeOrder(prevState: CheckoutState, formData: FormData) {
    // 1. Auth check
    const store = await cookies();
    const token =  store.get(AUTH_COOKIE)?.value;
    if (!token) redirect("/login?next=/checkout");

    // 2. Validate input - narrow + length check
    const raw = formData.get("shippingAddress");
    if (typeof raw !== "string" || raw.trim().length < 5){
        return {error: "Shipping address must be at least 5 characters"};
    }

    const shippingAddress = raw.trim();

    // 3. Call backend
    try{
        const res = await authFetch(token, `${BACKEND_URL}/api/orders/checkout`, {
            method: "POST",
            headers: {"Content-Type":"application/json"},
            body: JSON.stringify({shippingAddress}),
        });
        if (!res.ok){
            const errBody = await res.text();
            return { error: errBody || "Checkout failed. Please try again." };
        }
    }catch(err){
        if (err instanceof TokenExpiredError){
            store.delete(AUTH_COOKIE);
            redirect("/login?next=/checkout");
        }
        throw err;
    }

    // 4. Invalidate caches
    revalidatePath("/");
    revalidatePath("/cart");
    revalidatePath("/orders");

    // 5.
    redirect("/orders?placed=1");
}

export async function cancelOrder(formData: FormData){
    const store = await cookies();
    const token = store.get(AUTH_COOKIE)?.value;
    if (!token) redirect("/login?next=/orders");

    const raw = formData.get("orderId");
    if (typeof raw !== "string") throw new Error("Invalid order ID");
    const orderId = raw;

    try {
        const res = await authFetch(token, `${BACKEND_URL}/api/orders/${orderId}/cancel`, {
            method: "PUT",
        });
        if (!res.ok) {
            const errBody = await res.text();
            throw new Error(`Cancel failed: ${errBody}`);
        }
    } catch (err) {
        if (err instanceof TokenExpiredError) {
            redirect(`/login?next=/orders/${orderId}`);
        }
        throw err;
    }

    revalidatePath("/orders");
    revalidatePath(`/orders/${orderId}`);


}

