import mongoose from 'mongoose';
import { ErrorTypes } from 'librechat-data-provider';
import { MongoMemoryServer } from 'mongodb-memory-server';
import { encrypt, decrypt } from '~/crypto';
import { createKeyMethods } from './key';
import { createModels } from '../models';

const userId = new mongoose.Types.ObjectId().toString();
jest.mock('~/crypto', () => ({
  encrypt: jest.fn(async (value: string) => `encrypted:${value}`),
  decrypt: jest.fn(async (value: string) => value.replace(/^encrypted:/, '')),
}));

const mockedEncrypt = encrypt as jest.MockedFunction<typeof encrypt>;
const mockedDecrypt = decrypt as jest.MockedFunction<typeof decrypt>;

let mongoServer: MongoMemoryServer;
let methods: ReturnType<typeof createKeyMethods>;

beforeAll(async () => {
  mongoServer = await MongoMemoryServer.create();
  await mongoose.connect(mongoServer.getUri());
  createModels(mongoose);
  methods = createKeyMethods(mongoose);
});

afterAll(async () => {
  await mongoose.disconnect();
  await mongoServer.stop();
});

beforeEach(async () => {
  await mongoose.models.Key.deleteMany({});
  jest.clearAllMocks();
});

describe('createKeyMethods', () => {
  it('encrypts values on update and never stores plaintext', async () => {
    await methods.updateUserKey({
      userId: userId,
      name: 'OpenCode Go',
      value: 'sk-secret',
    });

    expect(mockedEncrypt).toHaveBeenCalledWith('sk-secret');
    const raw = (await mongoose.models.Key.findOne({
      userId,
      name: 'OpenCode Go',
    }).lean()) as { value?: string } | null;
    expect(raw?.value).toBe('encrypted:sk-secret');
  });

  it('returns the decrypted value for a stored key', async () => {
    await mongoose.models.Key.create({
      userId: userId,
      name: 'OpenCode Go',
      value: 'encrypted:sk-secret',
    });

    await expect(methods.getUserKey({ userId: userId, name: 'OpenCode Go' })).resolves.toBe(
      'sk-secret',
    );
  });

  it('throws NO_USER_KEY when no key is stored', async () => {
    await expect(methods.getUserKey({ userId: userId, name: 'OpenCode Go' })).rejects.toThrow(
      ErrorTypes.NO_USER_KEY,
    );
  });

  it('maps decryption failures to INVALID_USER_KEY instead of leaking the crypto error', async () => {
    await mongoose.models.Key.create({
      userId: userId,
      name: 'OpenCode Go',
      value: 'not-decryptable',
    });
    mockedDecrypt.mockRejectedValueOnce(
      new Error('The operation failed for an operation-specific reason'),
    );

    await expect(methods.getUserKey({ userId: userId, name: 'OpenCode Go' })).rejects.toThrow(
      ErrorTypes.INVALID_USER_KEY,
    );
  });

  it('parses decrypted key values into an object', async () => {
    await mongoose.models.Key.create({
      userId: userId,
      name: 'OpenCode Go',
      value: `encrypted:${JSON.stringify({
        apiKey: 'sk-secret',
        baseURL: 'https://opencode.ai/zen/go/v1',
      })}`,
    });

    await expect(
      methods.getUserKeyValues({ userId: userId, name: 'OpenCode Go' }),
    ).resolves.toEqual({ apiKey: 'sk-secret', baseURL: 'https://opencode.ai/zen/go/v1' });
  });

  it('maps decryption failures in getUserKeyValues to INVALID_USER_KEY', async () => {
    await mongoose.models.Key.create({
      userId: userId,
      name: 'OpenCode Go',
      value: 'not-decryptable',
    });
    mockedDecrypt.mockRejectedValueOnce(new Error('bad decrypt'));

    await expect(methods.getUserKeyValues({ userId: userId, name: 'OpenCode Go' })).rejects.toThrow(
      ErrorTypes.INVALID_USER_KEY,
    );
  });

  it('maps malformed decrypted values to INVALID_USER_KEY', async () => {
    await mongoose.models.Key.create({
      userId: userId,
      name: 'OpenCode Go',
      value: 'encrypted:not-json',
    });

    await expect(methods.getUserKeyValues({ userId: userId, name: 'OpenCode Go' })).rejects.toThrow(
      ErrorTypes.INVALID_USER_KEY,
    );
  });
});
