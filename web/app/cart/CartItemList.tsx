"use client";
import {useOptimistic} from "react";
import type {components} from "@/lib/api/types";
import type {CartItem, CartOptimisticAction} from "@/lib/cart/types";
import CartRow from "@/app/cart/CartRow";

type Product = components["schemas"]["ProductResponse"];
type Props = {
    items: CartItem[]; products: Product[];
};

export default function CartItemList({items, products}: Props) {
    const [optimisticItems, dispatch] = useOptimistic<CartItem[], CartOptimisticAction>(
        items,
        (current, action) => {
            switch (action.type) {
                case "remove":
                    return current.filter((i) => i.productId !== action.productId);
                case "update":
                    return current.map((i) =>
                        i.productId === action.productId
                            ? { ...i, quantity: action.quantity }
                            : i,
                    );
            }
        },
    );
    return (<ul className="space-y-4 mb-8">
        {optimisticItems.map((item, idx) => {
            const product = products[idx];
            if (!product) return null;// 可能 optimistic 減咗 item，product array 仲在但 item 唔在
            return (<CartRow
                key={item.productId}
                item={item}
                product={product}
                onOptimisticAction={dispatch}
            />);
        })}
    </ul>);
}
