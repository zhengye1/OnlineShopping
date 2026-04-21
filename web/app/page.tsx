import Link from "next/link"; // Next.js Link
import { getFeed } from "@/lib/api/client";
import type { components } from "@/lib/api/types";

type Product = components["schemas"]["ProductResponse"];               // type alias

export const dynamic = "force-dynamic";

// ③ Component 名大寫開頭。Props 用 destructuring 拎出 `product`。
function ProductCard({ product }: { product: Product }) {
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

export default async function Home() {
  const feed = await getFeed();
  const { featuredProducts = [], newArrivals = [], categories = [] } = feed;

  return (
    <main className="mx-auto max-w-6xl px-6 py-12">
      <h1 className="text-3xl font-semibold mb-8">Online Shopping</h1>

      <section className="mb-12">
        <h2 className="text-xl font-medium mb-4">Featured</h2>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {featuredProducts.map((p) => (                               // ⑤ .map + key
            <ProductCard key={p.id} product={p} />
          ))}
        </div>
      </section>

      <section className="mb-12">
        <h2 className="text-xl font-medium mb-4">New Arrivals</h2>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {newArrivals.map((p) => (
            <ProductCard key={p.id} product={p} />
          ))}
        </div>
      </section>

      <section>
        <h2 className="text-xl font-medium mb-4">Categories</h2>
        <ul className="flex flex-wrap gap-2">
          {categories.map((c) => (
            <li
              key={c.id}
              className="px-3 py-1 rounded-full bg-gray-100 text-sm text-gray-700"
            >
              {c.name}
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}
