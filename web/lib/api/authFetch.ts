export class TokenExpiredError extends Error {
    constructor() {
        super("Token expired");
        this.name = "TokenExpiredError";
    }
}

export async function authFetch(token: string, url: string, init?: RequestInit){
    const res = await fetch(url, {
        ...init,
        headers: {
            ...init?.headers,
            Authorization: `Bearer ${token}`,
        },
        cache: "no-store",
    });

    if (res.status === 401) {
        // Backend 唔認個 token → 即場清 cookie
        throw new TokenExpiredError();
    }

    return res;
}