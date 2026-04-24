"use client"

import {useActionState} from "react";
import {useSearchParams} from "next/navigation";
import {login} from "@/lib/auth/actions";
import Link from "next/link";

export default function LoginForm() {
    const searchParams = useSearchParams();
    const next = searchParams.get("next") ?? "";
    const [state, formAction, pending] = useActionState(
        async (_prev: { error?: string } | null, formData: FormData) => {
            return await login(formData)
        }, null
    );

    return (
        <form action={formAction} className="space-y-4 max-w-sm">
            <input type="hidden" name="next" value = {next}/>
            <div>
                <label className="block text-sm font-medium mb-1">Username</label>
                <input
                    name="username"
                    type="text"
                    required
                    className="w-full px-3 py-2 border border-gray-300 rounded"/>
            </div>
            <div>
                <label className="block text-sm font-medium mb-1">Password</label>
                <input
                    name="password"
                    type="password"
                    required
                    className="w-full px-3 py-2 border border-gray-300 rounded"/>
            </div>
            {state?.error && (
                <p className="text-sm text-red-600">{state.error}</p>
            )}

            <button
                type="submit"
                disabled={pending}
                className="w-full py-2 rounded bg-blue-600 text-white font-medium hover:bg-blue-700 disabled:bg-gray-300">
                {pending ? "Signing in..." : "Sign in"}
            </button>
            <p className="text-sm text-gray-600">
                No account?{" "}
                <Link href="/register" className="text-blue-600 hover:underline">Sign up</Link>
            </p>
        </form>
    );
}