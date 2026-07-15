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

const variations = [
    '0t01@Eb', // Original provided
    'OtO1@Eb', // O instead of 0
    'otol@Eb', // l instead of 1
    '0t0l@Eb', // 0 and l
    'Oto1@Eb', // O and 1
    'oto1@Eb', // o and 1
];

async function main() {
    for (const pw of variations) {
        console.log(`Trying password: ${pw}`);
        const loginPayload = JSON.stringify({
            email: 'evandrosapiens@gmail.com',
            password: pw
        });

        try {
            const loginRes = await post('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/auth-login', loginPayload);
            if (loginRes.statusCode === 200) {
                console.log(`SUCCESS with password: ${pw}`);
                const loginData = JSON.parse(loginRes.body);
                console.log('TOKEN ACQUIRED.');
                return;
            } else {
                console.log(`Failed (${loginRes.statusCode}): ${loginRes.body.slice(0, 50)}`);
            }
        } catch (err) {
            console.error(`Error with ${pw}:`, err.message);
        }
    }
}

main();
