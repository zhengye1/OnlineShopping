import Link from "next/link";
import { getProduct } from "@/lib/api/client";
import AddToCartButton from "./AddToCartButton"; // ⑨ server page 引入 client component

export default async function ProductPage({
  params,
}: {
  params: Promise<{ id: string }>;                                // Next.js 16: params 係 Promise
}) {
  const { id } = await params;
  const product = await getProduct(Number(id));

  const price = ((product.price ?? 0) / 100).toFixed(2);

  return (
    <main className="mx-auto max-w-5xl px-6 py-8">
      <Link
        href="/"
        className="inline-block text-sm text-gray-500 hover:text-blue-600 mb-6"
      >
        ← Back to home
      </Link>

      <div className="grid md:grid-cols-2 gap-8"> {/* Tailwind grid：mobile 1-col，md+ 2-col */}
        <div className="aspect-square bg-gray-100 rounded-lg flex items-center justify-center overflow-hidden">
          {product.imageUrl ? ( // ① ternary
            <img
              src={product.imageUrl}
              alt={product.name}
              className="w-full h-full object-cover"
            />
          ) : (
            <span className="text-gray-400">No image</span>
          )}
        </div>

        <div>
          <p className="text-sm text-gray-500">{product.categoryName}</p>
          <h1 className="text-3xl font-semibold mt-1">{product.name}</h1>
          <p className="text-3xl text-blue-600 font-semibold mt-3">${price}</p>

          <p className="mt-6 text-gray-700 leading-relaxed">
            {product.description}
          </p>

          {/* ⑨ Server parent 渲染 client child，data 當 props 塞落去 */}
          <AddToCartButton
            productId={product.id!}
            stock={product.stock ?? 0}
          />
        </div>
      </div>
    </main>
  );
}
