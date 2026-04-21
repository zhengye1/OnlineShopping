import Link from "next/link";
import AuthButton from "./AuthButton";

// Server component（冇 'use client'）。純靜態導航，零 JS 落 browser。
// Lesson 28 再加 client cart badge 拎真正 count。
export default function Header() {
  return (
    <header className="sticky top-0 z-40 bg-white/90 backdrop-blur border-b border-gray-200">
      <div className="mx-auto max-w-6xl px-6 h-14 flex items-center justify-between">
        {/* 左：Logo → 返主頁 */}
        <Link href="/" className="font-semibold text-lg text-gray-900 hover:text-blue-600">
          🛍️ Shop
        </Link>

        {/* 中：Nav links */}
        <nav className="hidden md:flex items-center gap-6 text-sm text-gray-700">
          <Link href="/" className="hover:text-blue-600">Home</Link>
          <Link href="/products" className="hover:text-blue-600">Products</Link>
          <Link href="/categories" className="hover:text-blue-600">Categories</Link>
        </nav>

        {/* 右：Cart + Login */}
        <div className="flex items-center gap-3">
          <Link
            href="/cart"
            className="relative p-2 text-gray-700 hover:text-blue-600"
            aria-label="Cart"
          >
            🛒
            {/* L28 stub：hardcode 0，之後接真 count */}
            <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-blue-600 text-white text-[10px] font-medium flex items-center justify-center">
              0
            </span>
          </Link>
          <AuthButton />
        </div>
      </div>
    </header>
  );
}
