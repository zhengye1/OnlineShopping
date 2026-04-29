"use client";

import {useActionState} from "react";
import {placeOrder} from "@/lib/orders/actions";
import {initialCheckoutState} from "@/lib/orders/types";

export default function CheckoutForm(){
    const [state, formAction, isPending] = useActionState(
        placeOrder,
        initialCheckoutState
    );
    return (
        <form action={formAction} className="mt-8 space-y-3">
            <h2 className="text-xl font-semibold">Shipping Address</h2>
            <textarea
                name="shippingAddress"
                required
                minLength={5}
                rows={3}
                placeholder="Street, city, postal code..."
                disabled={isPending}
                className="w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:outline-none
  disabled:bg-gray-100"
            />

            {/* 🆕 Inline error display */}
            {state.error && (
                <p className="text-sm text-red-600">⚠️ {state.error}</p>
            )}

            <button
                type="submit"
                disabled={isPending}
                className="rounded-md bg-blue-600 px-6 py-3 text-white font-semibold hover:bg-blue-700 disabled:bg-gray-400
  disabled:cursor-not-allowed"
            >
                {isPending ? "Placing order..." : "Place Order"}
            </button>
        </form>
    );
}