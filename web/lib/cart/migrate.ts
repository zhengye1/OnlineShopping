import type {Cart} from "@/lib/cart/types";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

/**
 * Migrate guest cart items to backend user cart.
 * Token: JWT from just-logged-in response.
 * Cart: parsed guest cart from cookie.
 *
 * Returns: # of items successfully migrated.
 */

export async function migrateCart(token: string, cart: Cart):Promise<number>{
    if (!cart.items || cart.items.length === 0) return 0;
    const results = await Promise.allSettled(
        cart.items.map(item =>
            fetch(`${API_BASE_URL}/api/cart`,{
                method: "POST",
                headers: {
                    "Content-Type":"application/json",
                    Authorization: `Bearer ${token}`,
                },
                body: JSON.stringify({
                    productId: item.productId,
                    quantity: item.quantity,
                }),
            })
        )
    );
    return results.filter(r =>
        r.status === "fulfilled" && r.value.ok).length;
}