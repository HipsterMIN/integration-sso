import axios from 'axios';

const EXT_BASE_URL = process.env.EXT_API_ENDPOINT || '';

const extInstance = axios.create({
	baseURL: EXT_BASE_URL,
	headers: {
		'Content-Type': 'application/json',
		'X-API-Key': process.env.EXT_API_KEY || '',
	},
});

export default extInstance;
