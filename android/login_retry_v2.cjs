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

const variations = [
    '0t01@Eb', // Original
    '0t0I@Eb', // I instead of 1
    'OtO1@Eb', // O instead of 0
    'OtOI@Eb', // O and I
    'ot01@Eb', // lowercase o?
    '0t01@EB', // uppercase B?
];

async function main() {
    for (const pw of variations) {
        console.log(`Trying: ${pw}`);
        const loginPayload = JSON.stringify({
            email: 'evandrosapiens@gmail.com',
            password: pw
        });

        try {
            const res = await post('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/auth-login', loginPayload);
            if (res.statusCode === 200) {
                console.log(`SUCCESS: ${pw}`);
                const data = JSON.parse(res.body);
                const token = data.access_token;

                console.log('Fetching 27/02 History...');
                const hist = await get('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/api-caixa?date=2026-02-27', token);
                console.log('--- JSON 27/02 ---');
                console.log(hist.body);

                console.log('Fetching No-Date History...');
                const histNoDate = await get('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/api-caixa', token);
                console.log('--- JSON NO DATE ---');
                console.log(histNoDate.body);
                return;
            } else {
                console.log(`Failed (${res.statusCode}): ${res.body}`);
            }
        } catch (err) {
            console.error(err.message);
        }
    }
}

main();
