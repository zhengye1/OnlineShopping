import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getOrderDetail } from "@/lib/orders/store";
import { cancelOrder } from "@/lib/orders/actions";
import { TokenExpiredError } from "@/lib/api/authFetch";
import type { Order } from "@/lib/orders/types";
import StatusBadge from "@/app/_components/StatusBadge";

export const dynamic = "force-dynamic";

const AUTH_COOKIE = "auth_token";
const CANCELLABLE_STATUSES = ["PENDING_PAYMENT", "PAID"];

export default async function OrderDetailPage({params,}: {
    params: Promise<{ id: string }>;
}) {
    const { id } = await params;

    // Auth gate
    const store = await cookies();
    const token = store.get(AUTH_COOKIE)?.value;
    if (!token) redirect(`/login?next=/orders/${id}`);

    // Fetch + handle expired token
    let order: Order;
    try {
        order = await getOrderDetail(id);
    } catch (err) {
        if (err instanceof TokenExpiredError) redirect(`/login?next=/orders/${id}`);
        throw err;
    }

    const canCancel = CANCELLABLE_STATUSES.includes(order.orderStatus);
    const date = new Date(order.createdAt).toLocaleString();

    return (
        <main className="mx-auto max-w-4xl px-6 py-12">
            <h1 className="text-3xl font-semibold mb-2">Order #{order.id}</h1>
            <p className="text-gray-500 mb-8">{date}</p>

            {/* Status row + Cancel form */}
            <div className="flex items-center justify-between mb-8">
                <StatusBadge status={order.orderStatus} />
                {canCancel && (
                    <form action={cancelOrder}>
                        <input type="hidden" name="orderId" value={order.id} />
                        <button
                            type="submit"
                            className="rounded-md bg-red-600 px-4 py-2 text-white text-sm font-semibold hover:bg-red-700"
                        >
                            Cancel Order
                        </button>
                    </form>
                )}
            </div>

            {/* Items */}
            <section className="mb-8">
                <h2 className="text-xl font-semibold mb-4">Items</h2>
                <ul className="divide-y divide-gray-200">
                    {order.items.map((item) => (
                        <li key={item.id} className="flex justify-between py-3">
                            <div>
                                <p className="font-medium">{item.productName}</p>
                                <p className="text-sm text-gray-600">
                                    ${(item.productPrice / 100).toFixed(2)} × {item.quantity}
                                </p>
                            </div>
                            <span className="font-medium">
                  ${(item.totalPrice / 100).toFixed(2)}
                </span>
                        </li>
                    ))}
                </ul>
            </section>

            {/* Receipt */}
            <section className="space-y-2 border-t pt-4 mb-8">
                <div className="flex justify-between text-gray-600">
                    <span>Subtotal</span>
                    <span>${(order.subtotal / 100).toFixed(2)}</span>
                </div>
                <div className="flex justify-between text-gray-600">
                    <span>Tax (13%)</span>
                    <span>${(order.tax / 100).toFixed(2)}</span>
                </div>
                <div className="flex justify-between text-lg font-bold pt-2 border-t">
                    <span>Total</span>
                    <span>${(order.total / 100).toFixed(2)}</span>
                </div>
            </section>

            {/* Shipping */}
            <section>
                <h2 className="text-xl font-semibold mb-2">Shipping Address</h2>
                <p className="text-gray-700 whitespace-pre-line">{order.shippingAddress}</p>
                {order.trackingNumber && (
                    <p className="mt-2 text-sm text-gray-600">
                        Tracking: {order.trackingNumber}
                    </p>
                )}
            </section>
        </main>
    );
}