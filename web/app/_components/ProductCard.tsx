import Link from "next/link";
import type { components } from "@/lib/api/types";

type Product = components["schemas"]["ProductResponse"];// type alias
// ③ Component 名大寫開頭。Props 用 destructuring 拎出 `product`。
export default function ProductCard({ product }: { product: Product }) {
    const price = ((product.price ?? 0) / 100).toFixed(2); // JS expression，之後喺 JSX 用 {price}
    return (
        <Link
            href={`/products/${product.id}`}                                 // ① { } 入面係 JS；backtick template literal
            className="block rounded-lg border border-gray-200 p-4 hover:shadow-md transition"
            //  ^^^^^^^^^ ② className 唔係 class
        >
            <div className="aspect-square w-full bg-gray-100 rounded mb-3 flex items-center justify-center text-gray-400 text-sm">
                {product.imageUrl ? (                                          // ① ternary 做 conditional render
                    <img src={product.imageUrl} alt={product.name} className="w-full h-full object-cover rounded" />
                ) : (
                    <span>No image</span>
                )}
            </div>
            <h3 className="font-medium text-gray-900 line-clamp-1">{product.name}</h3>
            <p className="text-blue-600 font-semibold mt-1">${price}</p>
        </Link>
    );
}