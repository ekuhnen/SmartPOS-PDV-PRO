const https = require('https');

function post(url, token, data) {
    return new Promise((resolve, reject) => {
        const u = new URL(url);
        const options = {
            hostname: u.hostname,
            path: u.pathname,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`,
                'Content-Length': Buffer.byteLength(data)
            }
        };
        const req = https.request(options, (res) => {
            let body = '';
            res.on('data', (d) => body += d);
            res.on('end', () => resolve({ body, statusCode: res.statusCode }));
        });
        req.on('error', reject);
        req.write(data);
        req.end();
    });
}

function postWithoutAuth(url, data) {
    return new Promise((resolve, reject) => {
        const u = new URL(url);
        const options = {
            hostname: u.hostname,
            path: u.pathname,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(data)
            }
        };
        const req = https.request(options, (res) => {
            let body = '';
            res.on('data', (d) => body += d);
            res.on('end', () => resolve({ body, statusCode: res.statusCode }));
        });
        req.on('error', reject);
        req.write(data);
        req.end();
    });
}

async function main() {
    console.log('Attempting login...');
    const loginPayload = JSON.stringify({
        email: 'evandrosapiens@gmail.com',
        password: '0t0n1@Eb'
    });

    try {
        const loginRes = await postWithoutAuth('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/auth-login', loginPayload);
        if (loginRes.statusCode !== 200) {
            console.error('Login Failed:', loginRes.body);
            return;
        }

        const loginData = JSON.parse(loginRes.body);
        const token = loginData.access_token;
        console.log('Login Successful.');

        console.log('--- TEST COMANDA LIST MESAS ---');
        const mesasRes = await fetch('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/api-mesas', {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });
        const mesasData = await mesasRes.json();
        
        console.dir(mesasData, {depth: null});
        // Find occupied mesa
        let targetComandaId = null;
        let targetProductId = null;
        if(mesasData && mesasData.setores) {
            for(let s of mesasData.setores) {
                for(let m of s.mesas) {
                    if(m.itens && m.itens.length > 0) {
                        targetComandaId = m.comanda_id;
                        targetProductId = m.itens[0].produto_id;
                        break;
                    }
                }
            }
        }
        
        if (!targetComandaId) {
            console.log("No occupied table found.");
            return;
        }
        
        console.log(`Canceling product ${targetProductId} on comanda ${targetComandaId}`);

        const req1 = JSON.stringify({
            action: 'cancel_item',
            comanda_id: targetComandaId, 
            item_id: targetProductId,
            qtd: 1
        });
        
        const res1 = await fetch('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/api-comandas', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
            },
            body: req1
        });
        
        console.log(`StatusCode: ${res1.status}`);
        const data = await res1.text();
        console.log(`Body: ${data}`);

    } catch (err) {
        console.error('Error:', err);
    }
}

main();
