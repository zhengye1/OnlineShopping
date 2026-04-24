"use client"

import {useState} from "react";
import {logout} from "@/lib/auth/actions";
import Link from "next/link";

export default function UserMenu({username}:{username: string }){
    const [menuOpen, setMenuOpen] = useState(false);
    return (
        <div className="relative">
            <button
                className="text-sm px-3 py-1.5 rounded-md hover:bg-gray-100"
                onClick={() => setMenuOpen(!menuOpen)}>
                👤 {username} ▾
            </button>
            {menuOpen && (
                <div className="absolute top-full right-0 mt-2 bg-white border border-gray-200 rounded-md
                shadow-lg py-1 min-w-35 z-50">
                    <Link href="/account"
                          className="block w-full text-left px-4 py-2 text-sm hover:bg-gray-100">
                        Account
                    </Link>
                    <form action={logout}>
                        <button
                            type="submit"
                            className="block w-full text-left px-4 py-2 text-sm hover:bg-gray-100">
                            Sign out
                        </button>
                    </form>
                </div>
            )}
        </div>
    );
}