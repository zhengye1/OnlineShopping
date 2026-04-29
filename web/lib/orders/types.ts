export type OrderItem = {
    id: number;
    productId: number;
    productName: string;
    productPrice: number; // cents
    quantity: number;
    totalPrice: number;
}

export type OrderStatus =
    | "PENDING_PAYMENT"
    | "PAID"
    | "SHIPPED"
    | "DELIVERED"
    | "COMPLETED"
    | "RETURNED"
    | "CANCELLED";

export type Order = {
    id: number;
    shippingAddress: string;
    trackingNumber: string | null;
    orderStatus: OrderStatus
    items: OrderItem[];
    subtotal: number;
    tax: number;
    total: number;
    createdAt: string; // ISO datetime
}
// State type
export type CheckoutState = { error: string | null };
export const initialCheckoutState: CheckoutState = { error: null };