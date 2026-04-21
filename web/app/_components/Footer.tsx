import Link from "next/link";

export default function Footer(){
    const links=[
        {label:"About", href:"/about"},
        {label:"Contact", href:"/contact"},
        {label:"FAQ", href:"/faq"}
    ];
    return (
        <footer className="border-t border-gray-200 bg-gray-50 mt-16">
            <div className="mx-auto max-w-6xl px-6 py-8">
            <div className=" grid grid-cols-1 md:grid-cols-3 gap-8">
                <div>
                    <h3 className="font-semibold text-gray-900 mb-3">Shop</h3>
                    <p className="text-sm text-gray-600 leading-relaxed">Your one-stop online shopping destination for electronics, fashion, and more.</p>
                </div>
                <div>
                    <h3 className="font-semibold text-gray-900 mb-3">Links</h3>
                    {links.map((link) => (
                        <Link className="block hover:text-blue-600" key={link.href} href={link.href}>{link.label}</Link>
                    ))}
                </div>
                <div>
                    <h3 className="font-semibold text-gray-900 mb-3">Follow us</h3>
                    {/* Social icons 用 flex gap */}
                    <div className="flex gap-3 text-xl">
                        <span>📘</span>
                        <span>📷</span>
                        <span>🐦</span>
                    </div>
                </div>
            </div>
            <div className="mt-8 pt-6 border-t text-center text-sm text-gray-500">
                © 2026 Shop. All rights reserved.
            </div>
            </div>
        </footer>
    );
}