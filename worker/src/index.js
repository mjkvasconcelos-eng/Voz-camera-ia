const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS, GET",
  "Access-Control-Allow-Headers": "Authorization, Content-Type",
  "Access-Control-Max-Age": "86400",
  "Content-Type": "application/json; charset=utf-8",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: corsHeaders });
}

function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function extractText(response) {
  const parts = [];
  for (const item of response.output || []) {
    if (item.type === "message") {
      for (const content of item.content || []) {
        if (content.type === "output_text" && content.text) parts.push(content.text);
      }
    }
  }
  return parts.join("\n").trim();
}

const SYSTEM_PROMPT = `Você é o Assistente Fotográfico do aplicativo Voz Câmera IA, especializado em fotografia móvel no Samsung Galaxy S24 Ultra.
Responda sempre em português do Brasil, de forma objetiva e prática.
Ajude a interpretar a cena e recomendar lente, enquadramento, ISO, velocidade do obturador, foco, balanço de branco e exposição.
Nunca afirme que alterou controles da câmera: apenas recomende valores. Considere que a abertura das lentes do telefone é fixa e que a disponibilidade de controles depende da câmera/lente ativa e do Camera2.
Quando analisar uma foto, descreva primeiro a cena e depois apresente recomendações em tópicos.
Se não houver dados suficientes, explique a limitação e peça apenas o dado essencial.`;

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "Voz Câmera IA", apiKeyConfigured: Boolean(env.OPENAI_API_KEY) });
    }

    if (request.method !== "POST" || !["/v1/chat", "/v1/analyze"].includes(url.pathname)) {
      return json({ error: "Rota não encontrada." }, 404);
    }

    const auth = request.headers.get("Authorization") || "";
    const suppliedToken = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    if (!env.APP_ACCESS_TOKEN || !safeEqual(suppliedToken, env.APP_ACCESS_TOKEN)) {
      return json({ error: "Acesso não autorizado." }, 401);
    }
    if (!env.OPENAI_API_KEY) return json({ error: "A chave da API ainda não foi configurada no servidor." }, 503);

    const contentLength = Number(request.headers.get("Content-Length") || "0");
    if (contentLength > 8_000_000) return json({ error: "Solicitação muito grande. Reduza o tamanho da imagem." }, 413);

    let body;
    try { body = await request.json(); }
    catch { return json({ error: "JSON inválido." }, 400); }

    const message = typeof body.message === "string" ? body.message.trim() : "";
    const context = typeof body.context === "string" ? body.context.slice(0, 1200) : "";
    if (!message || message.length > 4000) return json({ error: "A mensagem deve ter entre 1 e 4.000 caracteres." }, 400);

    const content = [{ type: "input_text", text: context ? message + "\n\nDados atuais da câmera: " + context : message }];
    if (url.pathname === "/v1/analyze") {
      const imageDataUrl = body.imageDataUrl;
      if (typeof imageDataUrl !== "string" || !/^data:image\/(jpeg|jpg|png|webp);base64,/i.test(imageDataUrl)) {
        return json({ error: "Imagem inválida. Envie JPEG, PNG ou WebP em Base64." }, 400);
      }
      if (imageDataUrl.length > 6_500_000) return json({ error: "Imagem acima do limite de 4,8 MB." }, 413);
      content.push({ type: "input_image", image_url: imageDataUrl, detail: "high" });
    }

    try {
      const apiResponse = await fetch("https://api.openai.com/v1/responses", {
        method: "POST",
        headers: {
          "Authorization": "Bearer " + env.OPENAI_API_KEY,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          model: env.OPENAI_MODEL || "gpt-4.1-mini",
          instructions: SYSTEM_PROMPT,
          input: [{ role: "user", content }],
          max_output_tokens: 700,
          store: false,
        }),
        signal: AbortSignal.timeout(55000),
      });

      const data = await apiResponse.json().catch(() => ({}));
      if (!apiResponse.ok) {
        return json({ error: data.error?.message || "A API da OpenAI não conseguiu processar a solicitação." }, apiResponse.status === 429 ? 429 : 502);
      }
      const answer = extractText(data);
      if (!answer) return json({ error: "A IA retornou uma resposta vazia. Tente novamente." }, 502);
      return json({ answer });
    } catch (error) {
      return json({ error: error?.name === "TimeoutError" ? "A análise demorou demais. Tente novamente." : "Falha ao conectar com o serviço de IA." }, 502);
    }
  },
};
