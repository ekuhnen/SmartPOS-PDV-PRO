const https = require('https');

function post(url, data, token) {
    return new Promise((resolve, reject) => {
        const u = new URL(url);
        const body = JSON.stringify(data);
        const options = {
            hostname: u.hostname,
            path: u.pathname,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(body),
                ...(token ? { 'Authorization': `Bearer ${token}` } : {})
            }
        };
        const req = https.request(options, (res) => {
            let resp = '';
            res.on('data', (d) => resp += d);
            res.on('end', () => resolve({ body: resp, statusCode: res.statusCode }));
        });
        req.on('error', reject);
        req.write(body);
        req.end();
    });
}

function get(url, token) {
    return new Promise((resolve, reject) => {
        const options = { headers: { 'Authorization': `Bearer ${token}` } };
        https.get(url, options, (res) => {
            let resp = '';
            res.on('data', (d) => resp += d);
            res.on('end', () => resolve({ body: resp, statusCode: res.statusCode }));
        }).on('error', reject);
    });
}

async function main() {
    const BASE = 'https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1';

    console.log('=== LOGIN ===');
    const loginRes = await post(`${BASE}/auth-login`, {
        email: 'evandrosapiens@gmail.com',
        password: '0t0n1@Eb'
    });
    if (loginRes.statusCode !== 200) {
        console.error('Login falhou:', loginRes.statusCode, loginRes.body);
        return;
    }
    const token = JSON.parse(loginRes.body).access_token;
    console.log('Login OK. Token obtido.');

    console.log('\n=== LISTAR MESAS ===');
    const mesasRes = await get(`${BASE}/api-mesas`, token);
    console.log('Status:', mesasRes.statusCode);
    const mesasData = JSON.parse(mesasRes.body);
    
    // Encontra a primeira mesa livre
    let mesaLivre = null;
    for (const setor of (mesasData.setores || [])) {
        for (const mesa of (setor.mesas || [])) {
            console.log(`  Mesa ${mesa.numero} (${setor.nome}) - status: ${mesa.status} - id: ${mesa.id}`);
            if (!mesaLivre && mesa.status === 'LIVRE') {
                mesaLivre = mesa;
            }
        }
    }

    if (!mesaLivre) {
        console.log('\nNenhuma mesa livre encontrada para teste.');
        return;
    }

    console.log(`\n=== TENTANDO ABRIR MESA ${mesaLivre.numero} (id: ${mesaLivre.id}) ===`);
    
    // Teste 1: com nome_cliente
    const payload1 = {
        action: 'abrir',
        mesa_id: mesaLivre.id,
        pessoas_qtd: 1,
        nome_cliente: 'Evandro Teste'
    };
    console.log('Payload enviado:', JSON.stringify(payload1, null, 2));
    const res1 = await post(`${BASE}/api-comandas`, payload1, token);
    console.log('Status:', res1.statusCode);
    console.log('Resposta:', res1.body);
}

main().catch(console.error);
