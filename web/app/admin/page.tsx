import {getSession} from "@/lib/auth/session";
import Link from "next/link";
import {redirect} from "next/navigation";

export const dynamic = "force-dynamic"; // getSession 需要讀cookie

export default async function AdminPage(){
    const session = await getSession();

    if (!session) {
        redirect("/login?next=/admin");
    }

    if (session.role !== "ADMIN"){
        return (
            <div>
                <h1>403 — Access Denied</h1>
                <p>This page is for administrators only.</p>
                <Link href="/">Go home</Link>
            </div>
        );
    }

    return (
        <main className="mx-auto max-w-6xl px-6 py-12">
            <h1 className="text-3xl font-semibold mb-8">Admin Dashboard</h1>
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