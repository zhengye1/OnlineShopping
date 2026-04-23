import {getCart} from "@/lib/cart/store";
import Link from "next/link";
import {getProduct} from "@/lib/api/client";
import {clearCart} from "@/lib/cart/actions";
import CartItemList from "./CartItemList";

export const dynamic = "force-dynamic"

export default async function CartPage() {
    const cart = await getCart();
    const {items = []} = cart;
    // ⬇️ Empty case 先 early return 走人 — JSX return 乾淨
    if (items.length === 0){
        return (
            <main className="mx-auto max-w-6xl px-6 py-12">
                <h1 className="text-3xl font-semibold mb-4">Your Cart</h1>
                <p className="text-gray-600 mb-6">Your cart is empty.</p>
                <Link href="/products" className="text-blue-600 hover:underline">
                    ← Browse products
                </Link>
            </main>
        )
    }

    // ⬇️ 呢度 items.length > 0，做 prep
    const products = await Promise.all(
        items.map(item => getProduct(item.productId))
    );

    const subtotal = items.reduce((sum, item , idx) =>{
        return sum + (products[idx].price ?? 0) * item.quantity;
    }, 0);
    // ⬇️ 然後乾淨 return
    return (
        <main className="mx-auto max-w-6xl px-6 py-12">
            <h1 className="text-3xl font-semibold mb-8">Your Cart</h1>
            <CartItemList items={items} products={products} />
            <div className="pt-6 border-t border-gray-200 flex justify-between items-center">
                <span className="text-lg font-semibold text-gray-900">Subtotal</span>
                <span className="text-2xl font-bold text-blue-600">
                    ${(subtotal / 100).toFixed(2)}
                </span>
            </div>
            <form action={clearCart} className="mt-4 flex justify-end">
                <button
                    type="submit"
                    className="text-sm text-red-600 hover:text-red-800 hover:underline">
                    Clear Cart
                </button>
            </form>
        </main>
    );
}