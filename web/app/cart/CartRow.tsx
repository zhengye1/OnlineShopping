"use client";

import type {components} from "@/lib/api/types";
import type {CartItem, CartOptimisticAction} from "@/lib/cart/types";
import {startTransition, useEffect, useState} from "react";
import {removeFromCart, updateQuantity} from "@/lib/cart/actions";

type Product = components["schemas"]["ProductResponse"];

type Props = {
    item: CartItem;
    product: Product;
    onOptimisticAction: (action: CartOptimisticAction) => void;  // ← 改 signature
}

const DEBOUNCE_MS = 500;

export default function CartRow({item, product, onOptimisticAction}: Props) {
    const [pendingQty, setPendingQty] = useState(item.quantity);
    const lineTotal = (product.price ?? 0) * pendingQty;

    useEffect(() => {
        if (pendingQty === item.quantity) return;
        const timer = setTimeout(() => {
            commitQty(pendingQty);
        }, DEBOUNCE_MS);
        return () => clearTimeout(timer);
    }, [pendingQty]);

    // 統一個update 入口
    async function commitQty(next: number) {
        if (next < 0) return;
        if (next === 0) {
            startTransition(() =>
                onOptimisticAction({ type: "remove", productId: item.productId }));
            await removeFromCart(item.productId);
            return;
        }
        const fd = new FormData();
        fd.set("quantity", String(next));
        startTransition(() =>
            onOptimisticAction({ type: "update", productId: item.productId, quantity: next })
        );
        await updateQuantity(item.productId, fd);
    }

    return (<li className="flex gap-4 p-4 border border-gray-200 rounded-lg">
        <div className="flex-1">
            <h3 className="font-medium text-gray-900">{product.name}</h3>

            {/* Stepper */}
            <div className="flex items-center gap-1 mt-2">
                <label className="text-sm text-gray-500 mr-2">Qty:</label>

                <button
                    type="button"
                    onClick={() => setPendingQty((prev) => Math.max(0, prev - 1))}
                    className="w-8 h-8 rounded-md border border-gray-300 hover:bg-gray-100 disabled:opacity-50"
                    aria-label="Decrease quantity"
                >
                    −
                </button>

                <input
                    type="number"
                    min={0}
                    value={pendingQty}
                    onChange={(e) => setPendingQty(Number(e.target.value))}
                    onBlur={() => {
                        if (pendingQty < 1) setPendingQty(item.quantity);
                    }}
                    className="w-14 px-2 py-1 border border-gray-300 rounded text-sm text-center"
                />

                <button
                    type="button"
                    onClick={() => setPendingQty((prev) => prev + 1)}
                    className="w-8 h-8 rounded-md border border-gray-300 hover:bg-gray-100"
                    aria-label="Increase quantity"
                >
                    +
                </button>
            </div>
        </div>

        <p className="font-semibold text-gray-900">${(lineTotal / 100).toFixed(2)}</p>

        <form action={async () => {
            onOptimisticAction({ type: "remove", productId: item.productId })
            await removeFromCart(item.productId);
        }}>
            <button type="submit" className="text-sm text-red-600 hover:text-red-800 hover:underline">
                Remove
            </button>
        </form>
    </li>);
}