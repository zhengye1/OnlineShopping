export type CartItem = {
    productId: number;
    quantity: number;
};

export type Cart = {
    items: CartItem[];
}

export type CartOptimisticAction =
    | { type: "remove"; productId: number }
    | { type: "update"; productId: number; quantity: number };
