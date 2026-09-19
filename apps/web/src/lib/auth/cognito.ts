import "server-only";

/**
 * O cliente do Cognito — e ele é um `fetch`, não um SDK.
 *
 * <h2>Por que não o `@aws-sdk/client-cognito-identity-provider`</h2>
 * Porque as duas operações usadas aqui (`InitiateAuth` com `USER_PASSWORD_AUTH` e com
 * `REFRESH_TOKEN_AUTH`) são <b>não autenticadas</b>: não assinam com SigV4, não precisam de
 * credencial nenhuma. O SDK traria ~2 MB para dentro do bundle da função do Lambda — que é o mesmo
 * bundle cujo cold start já é o número que dói nesta stack — para montar um POST com dois cabeçalhos.
 *
 * <h2>Por que só no SERVIDOR (`server-only`)</h2>
 * Não é o client id que é segredo — ele é público por definição num client sem secret. É que o
 * <b>token</b> não deve passar pelo JavaScript da página: ele nasce aqui, vai para um cookie
 * `httpOnly` e só volta ao navegador quando uma server action o entrega, já em memória. O import de
 * `server-only` transforma "alguém importou isto num componente de cliente" em erro de BUILD.
 *
 * <h2>A senha NÃO vai por `/oauth2/token`</h2>
 * O endpoint OAuth2 do Cognito aceita `authorization_code`, `client_credentials` e `refresh_token` —
 * e não `password`. Senha vai pela API própria do serviço, que é esta. É o mesmo caminho que o
 * `infra/scripts/e2e.sh` usa com `aws cognito-idp initiate-auth`.
 */

const REGION = process.env.COGNITO_REGION ?? "us-east-1";
const CLIENT_ID = process.env.COGNITO_CLIENT_ID ?? "";

export type AuthTokens = {
    idToken: string;
    accessToken: string;
    refreshToken?: string;
    expiresIn: number;
};

/** O erro que chega ao formulário. `code` é o `__type` do Cognito, e é o que distingue os casos. */
export class CognitoError extends Error {
    constructor(
        readonly code: string,
        message: string,
    ) {
        super(message);
        this.name = "CognitoError";
    }
}

type InitiateAuthResponse = {
    AuthenticationResult?: {
        IdToken: string;
        AccessToken: string;
        RefreshToken?: string;
        ExpiresIn: number;
    };
    ChallengeName?: string;
    Session?: string;
};

async function initiateAuth(
    flow: "USER_PASSWORD_AUTH" | "REFRESH_TOKEN_AUTH",
    parameters: Record<string, string>,
): Promise<AuthTokens> {
    if (!CLIENT_ID) {
        throw new CognitoError(
            "MissingConfiguration",
            "COGNITO_CLIENT_ID não está configurado neste ambiente.",
        );
    }

    const response = await fetch(`https://cognito-idp.${REGION}.amazonaws.com/`, {
        method: "POST",
        headers: {
            "content-type": "application/x-amz-json-1.1",
            "x-amz-target": "AWSCognitoIdentityProviderService.InitiateAuth",
        },
        body: JSON.stringify({
            AuthFlow: flow,
            ClientId: CLIENT_ID,
            AuthParameters: parameters,
        }),
        cache: "no-store",
    });

    const payload = (await response.json().catch(() => ({}))) as InitiateAuthResponse & {
        __type?: string;
        message?: string;
    };

    if (!response.ok) {
        const code = (payload.__type ?? "UnknownError").split("#").pop() ?? "UnknownError";
        throw new CognitoError(code, payload.message ?? `Cognito respondeu ${response.status}.`);
    }

    // Um challenge não é erro de HTTP: o Cognito responde 200 pedindo o próximo passo. Os três
    // usuários semeados têm senha PERMANENTE (`infra/aws/identity/index.ts` usa `password`, não
    // `temporaryPassword`), então isto só acontece se alguém criar um usuário à mão pelo console.
    if (!payload.AuthenticationResult) {
        throw new CognitoError(
            payload.ChallengeName ?? "ChallengeRequired",
            `O Cognito pediu um desafio (${payload.ChallengeName ?? "?"}) que este cliente não implementa.`,
        );
    }

    const result = payload.AuthenticationResult;
    return {
        idToken: result.IdToken,
        accessToken: result.AccessToken,
        refreshToken: result.RefreshToken,
        expiresIn: result.ExpiresIn,
    };
}

export function signInWithPassword(email: string, password: string): Promise<AuthTokens> {
    return initiateAuth("USER_PASSWORD_AUTH", { USERNAME: email, PASSWORD: password });
}

/**
 * Renova o ID token. O Cognito NÃO devolve um refresh token novo aqui — o antigo continua valendo
 * pelos 30 dias do default, e por isso quem chama preserva o cookie que já tem.
 */
export function refreshTokens(refreshToken: string): Promise<AuthTokens> {
    return initiateAuth("REFRESH_TOKEN_AUTH", { REFRESH_TOKEN: refreshToken });
}

export const cognitoConfig = {
    region: REGION,
    clientId: CLIENT_ID,
    issuer: `https://cognito-idp.${REGION}.amazonaws.com`,
};
