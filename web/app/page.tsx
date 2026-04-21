import {getFeed} from "@/lib/api/client";
import ProductCard from "./_components/ProductCard";

export const dynamic = "force-dynamic";
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
