"use client";
import {useOptimistic} from "react";
import {removeFromCart, updateQuantity} from "@/lib/cart/actions";
import type {components} from "@/lib/api/types";
import type {CartItem} from "@/lib/cart/types";

type Product = components["schemas"]["ProductResponse"];
type Props = {
    items: CartItem[];
    products: Product[];
};

export default function CartItemList({items, products} : Props){
    const [optimisticItems, applyOptimistic] = useOptimistic(
        items,
        (current: CartItem[], removeId: number) =>
            current.filter(i => i.productId !== removeId)
    );
    return (
        <ul className="space-y-4 mb-8">
            {optimisticItems.map((item, idx) => {
                const product = products[idx];
                if (!product) return null;// 可能 optimistic 減咗 item，product array 仲在但 item 唔在
                const lineTotal = (product.price ?? 0) * item.quantity;
                return (
                    <li
                        key={item.productId}
                        className="flex gap-4 p-4 border border-gray-200 rounded-lg">
                        <div className="flex-1">
                            <h3 className="font-medium text-gray-900">{product.name}</h3>
                            <form
                                action={updateQuantity.bind(null, item.productId)}
                                className="flex items-center gap-2 mt-1">
                                <label className="text-sm text-gray-500">Qty:</label>
                                <input
                                    name="quantity"
                                    type="number"
                                    defaultValue={item.quantity}
                                    min="0"
                                    className="w-16 px-2 py-1 border border-gray-300 rounded text-sm" />
                                <button
                                    type="submit"
                                    className="text-sm text-blue-600 hover:text-blue-800 hover:underline">
                                    Update
                                </button>
                            </form>
                        </div>
                        <p className="font-semibold text-gray-900">
                            ${(lineTotal / 100).toFixed(2)}
                        </p>
                        <form
                            action={async () => {
                                applyOptimistic(item.productId);// ✅ 即時 UI 消失
                                await removeFromCart(item.productId) // ✅ Background server action
                            }}>
                            <button
                                type="submit"
                                className="text-sm text-red-600 hover:text-red-800 hover:underline">
                                Remove
                            </button>
                        </form>
                    </li>
                );
            })}
        </ul>
    );
}