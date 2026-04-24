import {cookies} from "next/headers";

export type Session = {
    username: string;
    role: string;
};

const AUTH_COOKIE = "auth_token";
function decodeJwtPayload(token: string) : Session | null {
    try {
        const [, payload] = token.split(".");
        if (!payload) return null;

        const decoded = Buffer.from(payload, "base64url").toString("utf-8");
        const claims = JSON.parse(decoded);

        // check expiration
        if (typeof claims.exp === "number" && claims.exp * 1000 < Date.now()){
            return null;
        }
        if (!claims.sub || !claims.role) return null;
        return {username: claims.sub, role: claims.role};
    } catch{
            return null; // Malformed / corrupt token
    }
}

export async function getSession(): Promise<Session | null>{
    const store = await cookies();
    const token = store.get(AUTH_COOKIE)?.value;
    if (!token) return null;
    return decodeJwtPayload(token)
}