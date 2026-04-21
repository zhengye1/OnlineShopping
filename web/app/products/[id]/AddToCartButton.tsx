"use client";                                                  // ⑥ 呢個 file 係 client component

import { useState } from "react";                              // ⑦ React hook

type Props = {
  productId: number;
  stock: number;
};

export default function AddToCartButton({ productId, stock }: Props) {
  const [quantity, setQuantity] = useState(1);                 // ⑦ useState
  const [added, setAdded] = useState(false);

  const outOfStock = stock <= 0;                               // 衍生值，每次 render 重算
  const canDecrease = quantity > 1;
  const canIncrease = quantity < stock;

  function handleAdd() {
    // Lesson 28 會 call POST /api/cart；今堂只做 UI skeleton
    console.log(`[skeleton] add product ${productId} × ${quantity}`);
    setAdded(true);
    setTimeout(() => setAdded(false), 1500);
  }

  return (
    <div className="mt-6 space-y-3">
      <div className="flex items-center gap-3">
        <button
          onClick={() => setQuantity(quantity - 1)}            // ⑧ onClick camelCase；俾 function reference
          disabled={!canDecrease}
          className="w-9 h-9 rounded border border-gray-300 text-lg disabled:opacity-40"
        >
          −
        </button>
        <span className="w-10 text-center font-medium">{quantity}</span>
        <button
          onClick={() => setQuantity(quantity + 1)}
          disabled={!canIncrease}
          className="w-9 h-9 rounded border border-gray-300 text-lg disabled:opacity-40"
        >
          +
        </button>
        <span className="text-sm text-gray-500 ml-2">
          {stock} in stock
        </span>
      </div>

      <button
        onClick={handleAdd}
        disabled={outOfStock}
        className="w-full py-3 rounded-md bg-blue-600 text-white font-medium hover:bg-blue-700 disabled:bg-gray-300 disabled:cursor-not-allowed transition"
      >
        {outOfStock ? "Out of stock" : added ? "✓ Added" : "Add to cart"}
        {/* ① 嵌套 ternary：先 check stock，再 check added state */}
      </button>
    </div>
  );
}
