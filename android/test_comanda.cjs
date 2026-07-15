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

        console.log('--- TEST COMANDA OPEN ---');
        const req1 = JSON.stringify({
            action: 'remove_item',
            mesa_id: '9158ecbe-12a8-444a-a006-258169956c36', 
            customer_name: 'Evandro'
        });
        
        const openRes = await post('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/api-comandas', token, req1);
        console.log('StatusCode:', openRes.statusCode);
        console.log('Body:', openRes.body);
    } catch (err) {
        console.error('Error:', err);
    }
}

main();
