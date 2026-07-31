import CryptoKit
import Foundation

private let invalidParameters: Int32 = -1
private let insufficientCapacity: Int32 = -2
private let cryptoFailure: Int32 = -3
private let authenticationFailure: Int32 = -4
private let tagSize = 16

private func data(_ pointer: UnsafeRawPointer?, _ length: Int32) -> Data? {
    guard length >= 0 else { return nil }
    if length == 0 { return Data() }
    guard let pointer else { return nil }
    return Data(bytes: pointer, count: Int(length))
}

private func copy(_ data: Data, to output: UnsafeMutableRawPointer?, capacity: Int32) -> Int32 {
    guard capacity >= data.count else { return insufficientCapacity }
    if data.isEmpty { return 0 }
    guard let output else { return invalidParameters }
    data.copyBytes(to: output.assumingMemoryBound(to: UInt8.self), count: data.count)
    return Int32(data.count)
}

@_cdecl("kquic_aes_gcm_seal")
public func kquicAesGcmSeal(
    keyPointer: UnsafeRawPointer?,
    keyLength: Int32,
    noncePointer: UnsafeRawPointer?,
    nonceLength: Int32,
    aadPointer: UnsafeRawPointer?,
    aadLength: Int32,
    plaintextPointer: UnsafeRawPointer?,
    plaintextLength: Int32,
    output: UnsafeMutableRawPointer?,
    outputCapacity: Int32
) -> Int32 {
    guard keyLength == 16, nonceLength == 12,
          let key = data(keyPointer, keyLength),
          let nonceData = data(noncePointer, nonceLength),
          let aad = data(aadPointer, aadLength),
          let plaintext = data(plaintextPointer, plaintextLength) else {
        return invalidParameters
    }
    guard outputCapacity >= plaintext.count + tagSize else { return insufficientCapacity }
    do {
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let box = try AES.GCM.seal(
            plaintext,
            using: SymmetricKey(data: key),
            nonce: nonce,
            authenticating: aad
        )
        return copy(box.ciphertext + box.tag, to: output, capacity: outputCapacity)
    } catch {
        return cryptoFailure
    }
}

@_cdecl("kquic_aes_gcm_open")
public func kquicAesGcmOpen(
    keyPointer: UnsafeRawPointer?,
    keyLength: Int32,
    noncePointer: UnsafeRawPointer?,
    nonceLength: Int32,
    aadPointer: UnsafeRawPointer?,
    aadLength: Int32,
    ciphertextPointer: UnsafeRawPointer?,
    ciphertextLength: Int32,
    output: UnsafeMutableRawPointer?,
    outputCapacity: Int32
) -> Int32 {
    guard keyLength == 16, nonceLength == 12, ciphertextLength >= tagSize,
          let key = data(keyPointer, keyLength),
          let nonceData = data(noncePointer, nonceLength),
          let aad = data(aadPointer, aadLength),
          let sealed = data(ciphertextPointer, ciphertextLength) else {
        return invalidParameters
    }
    guard outputCapacity >= sealed.count - tagSize else { return insufficientCapacity }
    do {
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let split = sealed.count - tagSize
        let box = try AES.GCM.SealedBox(
            nonce: nonce,
            ciphertext: sealed.prefix(split),
            tag: sealed.suffix(tagSize)
        )
        let plaintext = try AES.GCM.open(
            box,
            using: SymmetricKey(data: key),
            authenticating: aad
        )
        return copy(plaintext, to: output, capacity: outputCapacity)
    } catch {
        return authenticationFailure
    }
}
