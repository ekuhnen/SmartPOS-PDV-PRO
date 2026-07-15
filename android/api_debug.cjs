const https = require('https');

function post(url, data) {
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

function get(url, token) {
    return new Promise((resolve, reject) => {
        const options = {
            headers: { 'Authorization': `Bearer ${token}` }
        };
        https.get(url, options, (res) => {
            let body = '';
            res.on('data', (d) => body += d);
            res.on('end', () => resolve({ body, statusCode: res.statusCode }));
        }).on('error', reject);
    });
}

async function main() {
    console.log('Attempting login with corrected password...');
    const loginPayload = JSON.stringify({
        email: 'evandrosapiens@gmail.com',
        password: '0t0n1@Eb'
    });

    try {
        const loginRes = await post('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/auth-login', loginPayload);
        if (loginRes.statusCode !== 200) {
            console.error('Login Failed:', loginRes.body);
            return;
        }

        const loginData = JSON.parse(loginRes.body);
        const token = loginData.access_token;
        console.log('Login Successful.');

        console.log('--- FETCHING HISTORY (LATEST) ---');
        const historyRes = await get('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/api-caixa', token);
        console.log(historyRes.body);

        console.log('--- FETCHING HISTORY (27/02) ---');
        const historyDateRes = await get('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/api-caixa?date=2026-02-27', token);
        console.log(historyDateRes.body);
    } catch (err) {
        console.error('Error:', err);
    }
}

main();
