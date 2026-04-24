import {getSession} from "@/lib/auth/session";
import Link from "next/link";
import UserMenu from "@/app/_components/UserMenu";

export default async function AuthButton(){
    const session = await getSession();
    if (!session) {
        return (
            <Link
                href="/login"
                className="text-sm px-3 py-1.5 rounded-md border
                border-gray-300 hover:border-blue-600 hover:text-blue-600">
                Sign in
            </Link>
        );
    }
    return <UserMenu username={session.username} />
}