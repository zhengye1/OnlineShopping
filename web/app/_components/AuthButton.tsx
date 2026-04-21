"use client";

import { useState } from "react"

export default function AuthButton(){
    const [isLoggedIn, setIsLoggedIn] = useState(false);
    const [menuOpen, setMenuOpen] = useState(false);
    return isLoggedIn ? (
        <div className="relative">
            <button
                className="text-sm px-3 py-1.5 rounded-md hover:bg-gray-100"
                onClick={() => setMenuOpen(!menuOpen)}>
                👤 User ▾
            </button>
            {menuOpen && (
                <div className="absolute top-full right-0 mt-2 bg-white border border-gray-200 rounded-md shadow-lg py-1 min-w-[140px]
   z-50">
                    <button className="block w-full text-left px-4 py-2 text-sm hover:bg-gray-100">My Orders</button>
                    <button
                        className="block w-full text-left px-4 py-2 text-sm hover:bg-gray-100"
                        onClick={
                        () => {
                            setIsLoggedIn(false);
                            setMenuOpen(false);
                        }
                    }>Sign out</button>
                </div>
            )}
        </div>
    ) :(
        <button
            className="text-sm px-3 py-1.5 rounded-md border border-gray-300 hover:border-blue-600 hover:text-blue-600"
            onClick={
                () => setIsLoggedIn(true)
            }>Sign in</button>
    );
}