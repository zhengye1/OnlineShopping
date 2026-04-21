import { getProducts } from "@/lib/api/client";
import ProductCard from "../_components/ProductCard";

export const dynamic = "force-dynamic";

export default async function ProductsPage(){
    const { content = []} = await getProducts(0, 20);

    return(
      <main className="mx-auto max-w-6xl px-6 py-12">
          <h1 className="text-3xl font-semibold mb-8">
              All Products
          </h1>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              {content.map((p) => (
                  <ProductCard key={p.id} product = {p} />
              ))}
          </div>
      </main>
    );
}