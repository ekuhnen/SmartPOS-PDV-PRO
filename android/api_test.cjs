const https = require('https');

const loginData = JSON.stringify({
    email: 'evandrosapiens@gmail.com',
    password: '0t01@Eb'
});

const options = {
    hostname: 'ypvcxgkzolzxggfrmzlz.supabase.co',
    path: '/functions/v1/auth-login',
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(loginData)
    }
};

const req = https.request(options, (res) => {
    let body = '';
    res.on('data', (d) => body += d);
    res.on('end', () => {
        try {
            const data = JSON.parse(body);
            if (!data.access_token) {
                console.error('Login failed:', body);
                process.exit(1);
            }
            const token = data.access_token;
            console.log('Login successful.');
            fetchHistory(token);
        } catch (e) {
            console.error('Error parsing login response:', body);
            process.exit(1);
        }
    });
});

req.on('error', (e) => console.error(e));
req.write(loginData);
req.end();

function fetchHistory(token) {
    const historyOptions = {
        hostname: 'ypvcxgkzolzxggfrmzlz.supabase.co',
        path: '/functions/v1/api-caixa?date=2026-02-27',
        method: 'GET',
        headers: {
            'Authorization': `Bearer ${token}`
        }
    };

    https.get(historyOptions, (res) => {
        let body = '';
        res.on('data', (d) => body += d);
        res.on('end', () => {
            console.log('--- CASHIER HISTORY ---');
            console.log(body);
        });
    }).on('error', (e) => console.error(e));
}
