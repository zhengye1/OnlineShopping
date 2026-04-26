import {NextRequest, NextResponse} from "next/server";

export function proxy(request: NextRequest){
    const {pathname} = request.nextUrl;

    // edge runtime 只check cookie ; verify signature 畀page做
    const token = request.cookies.get("auth_token")?.value;
    if (token) return NextResponse.next();

    // 未auth -> redirect /login?next=<origin>
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("next", pathname);
    return NextResponse.redirect(loginUrl);
}

export const config ={
    matcher: ["/account/:path*", "/orders/:path*", "/admin/:path*"],
};