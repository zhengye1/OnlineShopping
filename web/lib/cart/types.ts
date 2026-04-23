export type CartItem = {
    productId: number;
    quantity: number;
};

export type Cart = {
    items: CartItem[];
}

