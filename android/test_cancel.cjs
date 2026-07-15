
async function testFlow() {
    try {
        const loginRes = await fetch('https://api.vendas.app.plugpdv.com.br/auth-login', {
            method: 'POST',
            body: JSON.stringify({ device_cod: 'TERM-001' })
        });
        const loginData = await loginRes.json();
        const token = loginData.access_token;
        
        console.log('Fetching mesas...');
        const mesasRes = await fetch('https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/api-mesas', {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const mesasData = await mesasRes.json();
        
        console.dir(mesasData, { depth: null });

    } catch (e) {
        console.error(e);
    }
}
testFlow();
