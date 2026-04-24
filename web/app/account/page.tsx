import {getSession} from "@/lib/auth/session";
import Link from "next/link";

export const dynamic = "force-dynamic"; // getSession 需要讀cookie

export default async function AccountPage(){
    const session = await getSession();

    // 未login
    if (!session) {
        return (
            <main className="mx-auto max-w-6xl px-6 py-12">
                <h1 className="text-3xl font-semibold mb-4">Account</h1>
                <p className="text-gray-600 mb-6">Please sign in to view your account.</p>
                <Link href="/login"
                      className="text-blue-600 hover:underline">
                    ← Sign in
                </Link>
            </main>
        );
    }

    // Logged in - show session info
    return (
        <main className="mx-auto max-w-6xl px-6 py-12">
            <h1 className="text-3xl font-semibold mb-8">Account</h1>
            <dl className="space-y-4">
                <div>
                    <dt className="text-sm text-gray-500">Username</dt>
                    <dd className="text-lg font-medium text-gray-900">{session.username}</dd>
                </div>
                <div>
                    <dt className="text-sm text-gray-500">Role</dt>
                    <dd className="text-lg font-medium text-gray-900">{session.role}</dd>
                </div>
            </dl>
        </main>
    );
}