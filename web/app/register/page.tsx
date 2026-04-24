import RegisterForm from "@/app/register/RegisterForm";

export default function RegisterPage(){
    return (
        <main className="mx-auto max-w-6xl px-6 py-12">
            <h1 className="text-3xl font-semibold mb-8">Create account</h1>
            <RegisterForm />
        </main>
    )
}