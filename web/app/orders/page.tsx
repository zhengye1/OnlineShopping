import {getMyOrders} from "@/lib/orders/store";
import Link from "next/link";
import OrderCard from "@/app/_components/OrderCard";
import {cookies} from "next/headers";
import {redirect} from "next/navigation";
import {TokenExpiredError} from "@/lib/api/authFetch";
import type {Order, OrderStatus} from "@/lib/orders/types";

export const dynamic = "force-dynamic"

const AUTH_COOKIE = "auth_token";
const ALL_STATUSES: OrderStatus[] = ["PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "COMPLETED", "RETURNED", "CANCELLED",];

export default async function OrdersPage({searchParams,}: {
    searchParams: Promise<{ status?: string; placed?: string }>
}) {
    const store = await cookies();
    const token = store.get(AUTH_COOKIE)?.value;
    if (!token) redirect("/login?next=/orders");

    let orders: Order[];
    try {
        orders = await getMyOrders();
    } catch (err) {
        if (err instanceof TokenExpiredError) redirect("/login?next=/orders");
        throw err;   // ← 必須 re-throw
    }

    if (orders.length === 0) {
        return (<main className="mx-auto max-w-6xl px-6 py-12">
            <h1 className="text-3xl font-semibold mb-4">Your Orders</h1>
            <p className="text-gray-600 mb-6">You haven't placed any orders yet.</p>
            <Link href="/products" className="text-blue-600 hover:underline">
                ← Browse products
            </Link>
        </main>)
    }

    // read filter
    const {status, placed} = await searchParams;
    const activeFilter: OrderStatus | null = status && ALL_STATUSES.includes(status as OrderStatus) ? (status as OrderStatus) : null;

    // apply filter
    const visibleOrders = activeFilter ? orders.filter((o) => o.orderStatus === activeFilter) : orders;

    return (<main className="mx-auto max-w-6xl px-6 py-12">
        <h1 className="text-3xl font-semibold mb-8">
            Your Orders
        </h1>
        {/* 🆕 Saga in-flight banner */}
        {placed === "1" && (
            <div className="mb-6 rounded-md bg-blue-50 border border-blue-200 p-4 text-sm text-blue-900">
                <p className="font-medium">Order is being processed</p>
                <p className="mt-1">
                    Your order may take a few seconds to appear below.
                    If you don't see it after 10 seconds, please refresh.
                    If it still doesn't appear, the order may have failed
                    (e.g. an item went out of stock) — check the cart and try again.
                </p>
            </div>)}
        {/* Filter pills */}
        <div className="mb-6 flex flex-wrap gap-2">
            <Link href="/orders"
                  className={`rounded-full px-3 py-1 text-sm border ${!activeFilter ? "bg-gray-900 text-white border-gray-900" : "bg-white text-gray-700 border-gray-300 hover:bg-gray-100"}`}>
                All ({orders.length})
            </Link>
            {ALL_STATUSES.map((s) => {
                const count = orders.filter((o) => o.orderStatus === s).length;
                if (count === 0) return null;
                return (<Link
                    key={s}
                    href={`/orders?status=${s}`}
                    className={`rounded-full px-3 py-1 text-sm border ${activeFilter === s ? "bg-gray-900 text-white border-gray-900" : "bg-white text-gray-700 border-gray-300 hover:bg-gray-100"}`}
                >
                    {s.replace("_", " ")} ({count})
                </Link>);
            })}
        </div>
        {/* Empty state for filtered */}
        {visibleOrders.length === 0 ? (<p className="text-gray-600">
            No orders {activeFilter ? `with status ${activeFilter}` : ""}.
        </p>) : (<div className="space-y-4">
            {visibleOrders.map((o) => <OrderCard key={o.id} order={o}/>)}
        </div>)}
    </main>);
}