import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import Header from "./_components/Header";                       // ⑫ 由 _components 私有資料夾 import

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Shop — Online Shopping",
  description: "Browse products and shop online",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <Header />                                               {/* ⑩ 每個 page 之前自動渲染 */}
        <main className="flex-1">{children}</main>               {/* ⑩ page 內容由 children slot 塞入 */}
      </body>
    </html>
  );
}
