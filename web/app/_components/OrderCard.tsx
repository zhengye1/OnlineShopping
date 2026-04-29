import Link from "next/link";
import type {Order} from "@/lib/orders/types";
import StatusBadge from "./StatusBadge";

export default function OrderCard({ order }: { order: Order }){
    const total = (order.total / 100).toFixed(2);
    const itemCount = order.items.length;
    const date = new Date(order.createdAt).toLocaleString();

    return (
        <Link
            href={`/orders/${order.id}`} // ① { } 入面係 JS；backtick template literal
            className="block rounded-lg border border-gray-200 p-4 hover:shadow-md transition"
            //  ^^^^^^^^^ ② className 唔係 class
        >
            <h3 className="font-medium text-gray-900 line-clamp-1">Order #{order.id}</h3>
            <StatusBadge status={order.orderStatus} />
            <p className="text-blue-600 font-semibold mt-1">${total}</p>
            <p>{date}</p>
            <p>Item Count: {itemCount}</p>

        </Link>
    )
}