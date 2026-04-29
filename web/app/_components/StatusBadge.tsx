import type {OrderStatus} from "@/lib/orders/types";

const STATUS_STYLES: Record<OrderStatus, { label: string; className: string }> = {
    PENDING_PAYMENT: { label: "Pending Payment", className: "bg-amber-100 text-amber-800" },
    PAID:            { label: "Paid",            className: "bg-blue-100 text-blue-800" },
    SHIPPED:         { label: "Shipped",         className: "bg-indigo-100 text-indigo-800" },
    DELIVERED:       { label: "Delivered",       className: "bg-green-100 text-green-800" },
    COMPLETED:       { label: "Completed",       className: "bg-emerald-100 text-emerald-800" },
    RETURNED:        { label: "Returned",        className: "bg-orange-100 text-orange-800" },
    CANCELLED:       { label: "Cancelled",       className: "bg-gray-100 text-gray-600" },
};

export default function StatusBadge({status} : {status : OrderStatus}){
    const {label, className} = STATUS_STYLES[status];
    return (
        <span className={`inline-block rounded-full px-3 py-1 text-xs font-semibold ${className}`}>
            {label}
        </span>
    )
}