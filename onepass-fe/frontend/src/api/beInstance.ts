import axios from 'axios';

const BE_BASE_URL = process.env.BE_API_ENDPOINT || '';

const beInstance = axios.create({
	baseURL: BE_BASE_URL,
	headers: {
		'Content-Type': 'application/json',
		'X-BE-API-Key': process.env.BE_API_KEY || '',
	},
});

export default beInstance;

export const beApiInstance = axios.create({
	baseURL: BE_BASE_URL,
	headers: {
		'Content-Type': 'application/json',
		'X-BE-API-Key': process.env.BE_API_KEY || '',
	},
});
