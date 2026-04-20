import { getFeed } from "@/lib/api/client";

export const dynamic = "force-dynamic";

export default async function Home() {
  const feed = await getFeed();
  const { featuredProducts = [], newArrivals = [], categories = [] } = feed;

    return (
    <main className="mx-auto max-w-3xl px-6 py-12 font-sans">
      <h1 className="text-3xl font-semibold mb-8">Online Shopping</h1>

      <section className="mb-10">
        <h2 className="text-xl font-medium mb-3">Featured</h2>
        <ul className="space-y-1">
          {featuredProducts.map((p) => (
            <li key={p.id}>
              {p.name} — ${p.price}
            </li>
          ))}
        </ul>
      </section>

      <section className="mb-10">
        <h2 className="text-xl font-medium mb-3">New Arrivals</h2>
        <ul className="space-y-1">
          {newArrivals.map((p) => (
            <li key={p.id}>
              {p.name} — ${p.price}
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h2 className="text-xl font-medium mb-3">Categories</h2>
        <ul className="space-y-1">
          {categories.map((c) => (
            <li key={c.id}>{c.name}</li>
          ))}
        </ul>
      </section>
    </main>
  );
}
