import {getCart} from "@/lib/cart/store";
import {redirect} from "next/navigation";
import {getProduct} from "@/lib/api/client";
import CartItemList from "@/app/cart/CartItemList";
import CheckoutForm from "./CheckoutForm";

export const dynamic = "force-dynamic"

export default async function CheckoutPage(){
    const cart = await getCart();
    const {items = []} = cart;
    if (items.length === 0) redirect("/cart");

    // ⬇️ 呢度 items.length > 0，做 prep
    const products = await Promise.all(
        items.map(item => getProduct(item.productId))
    );

    const subtotal = items.reduce((sum, item , idx) =>{
        return sum + (products[idx].price ?? 0) * item.quantity;
    }, 0);


    const tax = subtotal * 13 / 100;
    const total = subtotal + tax;

    return (
        <main className="mx-auto max-w-6xl px-6 py-12">
            <h1 className="text-3xl font-semibold mb-8">Order Summary</h1>
            <CartItemList items={items} products={products} />
            <div className="space-y-2 border-t pt-4">
                <div className="flex justify-between text-gray-600">
                    <span>Subtotal</span>
                    <span>${(subtotal / 100).toFixed(2)}</span>
                </div>
                <div className="flex justify-between text-gray-600">
                    <span>Tax (13%)</span>
                    <span>${(tax / 100).toFixed(2)}</span>
                </div>
                <div className="flex justify-between text-lg font-bold pt-2 border-t">
                    <span>Total</span>
                    <span>${(total / 100).toFixed(2)}</span>
                </div>
            </div>
            <CheckoutForm />
        </main>
    )
}